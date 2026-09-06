package uskoag.wallet.daemon;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.OptionalLong;

/**
 * Which process is on the other end of a loopback connection, asked of the kernel rather than of the
 * caller.
 *
 * <p>Everything else a client tells the wallet about itself is a claim: the profile, the command line,
 * the working directory, the pid on {@code AccessRequest}. {@link uskoag.wallet.wire.CallerInfo} records
 * why none of it may be acted on. This is the exception, and the reason is that a client is a client —
 * it opened a socket, that socket has a local port, and Windows records which process owns it. A process
 * cannot make its socket appear to belong to another process, so when this answers, it is a fact.
 *
 * <p>{@code GetExtendedTcpTable} is machine-wide, so a loopback connection appears in it twice: once from
 * the client's side and once from ours. We hold the client's ephemeral port from the exchange and our own
 * listening port, so the row wanted is the one whose local port is theirs and whose remote port is ours.
 *
 * <p>IPv4 only, deliberately. The wallet binds the loopback address and every client reaches it at the
 * literal {@code 127.0.0.1} written into the handshake, so the v6 table would never hold the row — and
 * its rows have a different shape. If that ever changes this returns empty and the caller falls back,
 * which is the failure mode this is built to have.
 *
 * <p>Windows only. On any other platform the lookup is simply unavailable and the caller uses what the
 * client declared, saying so. The Linux equivalent is {@code /proc/net/tcp} plus a scan of
 * {@code /proc/*}/fd for the matching socket inode, and macOS needs {@code libproc}; on both, moving the
 * control channel to a Unix domain socket and reading {@code SO_PEERCRED} / {@code LOCAL_PEERPID} would
 * be better than either. Windows has AF_UNIX but passes no credentials over it, so it needs this route
 * regardless. Recorded rather than built, because the keyring is sealed with DPAPI and this wallet does
 * not run anywhere else yet.
 */
public final class PeerProcess {

    private static final int AF_INET = 2, TCP_TABLE_OWNER_PID_ALL = 5;
    private static final int ERROR_INSUFFICIENT_BUFFER = 122, NO_ERROR = 0, ESTABLISHED = 5;

    /** MIB_TCPTABLE_OWNER_PID: a count, then rows of six DWORDs. */
    private static final int ROWS_AT = 4, ROW = 24;
    private static final int STATE = 0, LOCAL_PORT = 8, REMOTE_PORT = 16, OWNER_PID = 20;

    private static final MethodHandle GET_TABLE;
    private static final String UNAVAILABLE;

    static {
        MethodHandle h = null;
        String why = null;
        try {
            var lib = SymbolLookup.libraryLookup("iphlpapi", Arena.global());
            h = Linker.nativeLinker().downcallHandle(lib.find("GetExtendedTcpTable").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        } catch (Throwable t) {
            why = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        GET_TABLE = h;
        UNAVAILABLE = why;
    }

    private PeerProcess() {
    }

    public static boolean available() {
        return GET_TABLE != null;
    }

    public static String unavailableReason() {
        return UNAVAILABLE == null ? "" : UNAVAILABLE;
    }

    /**
     * The pid owning the connection from {@code peerPort} to {@code ourPort}, or empty.
     *
     * <p>Empty is an ordinary answer, not an error: the platform may not be Windows, the connection may
     * have gone, the table may be racing. Nothing may be refused on the strength of it — a caller that
     * failed closed here would block honest tools and stop nobody.
     */
    public static OptionalLong of(int peerPort, int ourPort) {
        if (GET_TABLE == null || peerPort <= 0 || ourPort <= 0) return OptionalLong.empty();
        try (var arena = Arena.ofConfined()) {
            var table = read(arena);
            if (table == null) return OptionalLong.empty();
            var rows = table.get(ValueLayout.JAVA_INT, 0);
            for (var i = 0; i < rows; i++) {
                var at = ROWS_AT + (long) i * ROW;
                if (table.get(ValueLayout.JAVA_INT, at + STATE) != ESTABLISHED) continue;
                if (port(table, at + LOCAL_PORT) != peerPort) continue;
                if (port(table, at + REMOTE_PORT) != ourPort) continue;
                var pid = table.get(ValueLayout.JAVA_INT, at + OWNER_PID) & 0xFFFFFFFFL;
                return pid == 0 ? OptionalLong.empty() : OptionalLong.of(pid);
            }
            return OptionalLong.empty();
        } catch (Throwable t) {
            Log.warn("could not read the TCP table to identify the caller: " + t);
            return OptionalLong.empty();
        }
    }

    /**
     * Asks for the size, allocates, asks again. Twice, because the table can grow between the two calls
     * on a busy machine and a second attempt is cheaper than being wrong about who is calling.
     */
    private static MemorySegment read(Arena arena) throws Throwable {
        var size = arena.allocate(ValueLayout.JAVA_INT);
        for (var attempt = 0; attempt < 2; attempt++) {
            size.set(ValueLayout.JAVA_INT, 0, 0);
            var probe = (int) GET_TABLE.invokeExact(MemorySegment.NULL, size, 0,
                    AF_INET, TCP_TABLE_OWNER_PID_ALL, 0);
            if (probe != ERROR_INSUFFICIENT_BUFFER) return null;
            var bytes = size.get(ValueLayout.JAVA_INT, 0) & 0xFFFFFFFFL;
            if (bytes <= 0 || bytes > (1 << 26)) return null;
            var table = arena.allocate(bytes);
            var got = (int) GET_TABLE.invokeExact(table, size, 0,
                    AF_INET, TCP_TABLE_OWNER_PID_ALL, 0);
            if (got == NO_ERROR) return table;
            if (got != ERROR_INSUFFICIENT_BUFFER) return null;
        }
        return null;
    }

    /**
     * A port out of a DWORD that holds it in network byte order in its low half — the {@code ntohs} the
     * C callers all write. Reading the DWORD little-endian gives the two bytes the wrong way round, so
     * they are put back.
     */
    private static int port(MemorySegment table, long at) {
        var raw = table.get(ValueLayout.JAVA_INT, at);
        return ((raw & 0xFF) << 8) | ((raw >>> 8) & 0xFF);
    }
}

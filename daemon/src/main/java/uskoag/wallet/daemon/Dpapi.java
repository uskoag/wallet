package uskoag.wallet.daemon;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Windows DPAPI through the FFM API — no JNI, no native dependency, no UI.
 *
 * <p>There is nothing to see when this runs. It has no prompt, no PIN and no dialog; Windows Hello is
 * a different mechanism entirely and is not involved. It binds a blob to this user SID on this machine,
 * so a stolen file is inert elsewhere.
 *
 * <p>The optional-entropy parameter is what makes it worth doing. A user-scope blob alone can be
 * decrypted off-machine by an attacker who also stole the DPAPI master key and knows the Windows
 * password — a published, exploited technique. Entropy is not part of that key chain, so supplying a
 * value that only ever exists in wallet memory closes it.
 */
public final class Dpapi {

    /** Never show a UI. Without this, a locked or headless session could block the wallet forever. */
    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    private static final int CB_OFFSET = 0, PB_OFFSET = 8, BLOB_SIZE = 16;

    private static final MethodHandle PROTECT, UNPROTECT, LOCAL_FREE;
    private static final String UNAVAILABLE;

    static {
        MethodHandle p = null, u = null, f = null;
        String why = null;
        try {
            var linker = Linker.nativeLinker();
            var crypt32 = SymbolLookup.libraryLookup("crypt32", Arena.global());
            var kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            var sig = FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
            p = linker.downcallHandle(crypt32.find("CryptProtectData").orElseThrow(), sig);
            u = linker.downcallHandle(crypt32.find("CryptUnprotectData").orElseThrow(), sig);
            f = linker.downcallHandle(kernel32.find("LocalFree").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        } catch (Throwable t) {
            why = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        PROTECT = p;
        UNPROTECT = u;
        LOCAL_FREE = f;
        UNAVAILABLE = why;
    }

    private Dpapi() {
    }

    public static boolean available() {
        return PROTECT != null;
    }

    public static String unavailableReason() {
        return UNAVAILABLE;
    }

    public static byte[] protect(byte[] data, byte[] entropy) {
        return call(PROTECT, data, entropy, "CryptProtectData");
    }

    public static byte[] unprotect(byte[] data, byte[] entropy) {
        return call(UNPROTECT, data, entropy, "CryptUnprotectData");
    }

    private static byte[] call(MethodHandle fn, byte[] data, byte[] entropy, String label) {
        if (fn == null) throw new IllegalStateException("DPAPI unavailable — " + UNAVAILABLE);
        try (var arena = Arena.ofConfined()) {
            var in = blob(arena, data);
            var ent = entropy == null || entropy.length == 0 ? MemorySegment.NULL : blob(arena, entropy);
            var out = arena.allocate(BLOB_SIZE);

            var ok = (int) fn.invokeExact(in, MemorySegment.NULL, ent,
                    MemorySegment.NULL, MemorySegment.NULL, CRYPTPROTECT_UI_FORBIDDEN, out);
            if (ok == 0) throw new IllegalStateException(label + " failed (win32)");

            var len = out.get(ValueLayout.JAVA_INT, CB_OFFSET);
            var ptr = out.get(ValueLayout.ADDRESS, PB_OFFSET);
            try {
                return ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);
            } finally {
                var ignored = (MemorySegment) LOCAL_FREE.invokeExact(ptr);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(label + " failed", t);
        }
    }

    private static MemorySegment blob(Arena arena, byte[] bytes) {
        var payload = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
        var b = arena.allocate(BLOB_SIZE);
        b.set(ValueLayout.JAVA_INT, CB_OFFSET, bytes.length);
        b.set(ValueLayout.ADDRESS, PB_OFFSET, payload);
        return b;
    }
}

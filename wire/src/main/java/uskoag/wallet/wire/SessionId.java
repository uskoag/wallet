package uskoag.wallet.wire;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Which run of work this is, so one approval can cover the five hundred invocations an agent makes
 * instead of prompting five hundred times.
 *
 * <p>Not a secret and not an authorization boundary — any process running as you can read a sibling's
 * environment block and parent-PID is spoofable on Windows. It is a correlation key, and security
 * comes from the socket ACL, the unlock and the approval. Random 128-bit rather than sequential only
 * so a process cannot guess a live id and ride an approved budget.
 *
 * <p>Configuration must never be a precondition: with nothing set we derive the session from process
 * ancestry, since all the invocations of one agent run share an ancestor.
 */
public final class SessionId {

    public static final String ENV = "UKAG_WALLET_SESSION";

    private static final SecureRandom RNG = new SecureRandom();

    private SessionId() {
    }

    public static String random() {
        var b = new byte[16];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** The environment's session when one was exported, else one derived from the nearest long-lived ancestor. */
    public static String current() {
        var env = System.getenv(ENV);
        if (env != null && !env.isBlank()) return env.trim();
        return fromAncestry().orElseGet(SessionId::random);
    }

    /**
     * Walks up to the outermost ancestor we can still see and keys on its pid plus start time. The pid
     * alone would be recycled by Windows; the pair is stable for the life of that process.
     */
    static Optional<String> fromAncestry() {
        try {
            var top = ProcessHandle.current();
            for (var p = top.parent(); p.isPresent(); p = p.get().parent()) top = p.get();
            var start = top.info().startInstant().map(i -> String.valueOf(i.toEpochMilli())).orElse("0");
            return Optional.of("anc-" + top.pid() + "-" + start);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** For the audit and the wording of the approval dialog. Never for authorization. */
    public static String peerCommand() {
        try {
            return ProcessHandle.current().info().commandLine().orElse("(unknown)");
        } catch (Exception e) {
            return "(unknown)";
        }
    }
}

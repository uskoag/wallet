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

    /** How much of a hand-written session id is kept. Long enough for a sentence, short enough for a column. */
    public static final int MAX_LABEL = 120;

    /**
     * The environment's session when one was exported, else one derived from the nearest long-lived
     * ancestor.
     *
     * <p>A client is encouraged to export something that reads as intent — {@code "invoice reconciliation,
     * March"} rather than a hex string. This value is what the approval dialog and the audit show, and
     * "which run of work is this" is a question a person answers from words, not from
     * {@code a3f9c1e0b7d24. }. It is a correlation key and never an authorization boundary, so there is
     * nothing lost by making it legible.
     *
     * <p>Sanitised rather than trusted: control characters are stripped and the length is capped, because
     * this string is rendered in a dialog a person is about to make a security decision in, and a caller
     * that can inject newlines into it can push the real question off the visible area.
     */
    public static String current() {
        var env = System.getenv(ENV);
        if (env != null && !env.isBlank()) return label(env);
        return fromAncestry().orElseGet(SessionId::random);
    }

    /** Printable, single-line, bounded. Never rejects — a bad label must not stop the work. */
    public static String label(String raw) {
        var clean = raw.trim().replaceAll("[\\p{Cntrl}\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s{2,}", " ").trim();
        if (clean.isEmpty()) return random();
        return clean.length() <= MAX_LABEL ? clean : clean.substring(0, MAX_LABEL - 1) + "…";
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

    /*
     * There was a peerCommand() here and it never once worked.
     *
     * It returned ProcessHandle.current().info().commandLine(), which on Windows is empty for a process's
     * own argv — arguments() is null and commandLine() is absent, measured on JDK 25 — so it fell through
     * to its "(unknown)" default on every call this project has ever made. The approval dialog printed
     * that string for six sessions and it read as a quirk of the display rather than as a fact nobody had.
     *
     * Windows will not tell us, so the caller does: uskoag.gservices.Caller, recorded from the tool's own
     * main(String[] args). Nothing here should try to infer it again.
     */
}

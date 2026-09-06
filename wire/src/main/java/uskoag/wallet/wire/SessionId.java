package uskoag.wallet.wire;

/**
 * The words a run of work goes by, and the process it belongs to.
 *
 * <p>Identity used to live here, derived from process ancestry when nothing was exported, and it did not
 * work. The walk went to the outermost ancestor it could see, which on a normal desktop is
 * {@code explorer.exe} — one session for an entire Windows login, so a permission granted for "this one
 * command" silently covered every process the user was running. When it stopped earlier instead, it
 * stopped on a shell that died with the command, and the same document was approved five times inside two
 * minutes. Both failures were live in the same keyring.
 *
 * <p>So identity moved to where it can be established rather than guessed: the wallet asks the kernel
 * which process opened the connection and walks the chain itself. What is left here is what a client is
 * genuinely better placed to supply — a name for the run, and optionally which process the run belongs to.
 *
 * <p>Neither is a secret and neither is an authorization boundary. Security comes from the socket ACL,
 * the unlock and the approval.
 */
public final class SessionId {

    public static final String ENV = "UKAG_WALLET_SESSION";

    /**
     * The process a run of work belongs to — an agent or a terminal, not this invocation.
     *
     * <p>Exported by a run that wants one approval to cover all of it. Unlike everything else a client
     * says about itself, the wallet can check this one: it knows the real calling process and confirms the
     * declared pid is an ancestor of it, so the most a caller can do is pick a point on its own true
     * chain. Left unset, the wallet chooses the anchor itself and nothing is lost but precision.
     */
    public static final String SOURCE_ENV = "UKAG_WALLET_SOURCE_PID";

    /** How much of a hand-written session label is kept. Long enough for a sentence, short for a column. */
    public static final int MAX_LABEL = 120;

    private SessionId() {
    }

    /**
     * The name exported for this run, or null.
     *
     * <p>A client is encouraged to export something that reads as intent — {@code "invoice reconciliation,
     * March"} rather than a hex string. This is what the approval dialog and the audit show, and "which
     * run of work is this" is a question a person answers from words.
     *
     * <p>Sanitised rather than trusted: control characters are stripped and the length is capped, because
     * this string is rendered in a dialog somebody is about to make a security decision in, and a caller
     * that can inject newlines into it can push the real question off the visible area.
     */
    public static String current() {
        var env = System.getenv(ENV);
        return env == null || env.isBlank() ? null : label(env);
    }

    /** Printable, single-line, bounded. Never rejects — a bad label must not stop the work. */
    public static String label(String raw) {
        var clean = raw.trim().replaceAll("[\\p{Cntrl}\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s{2,}", " ").trim();
        if (clean.isEmpty()) return null;
        return clean.length() <= MAX_LABEL ? clean : clean.substring(0, MAX_LABEL - 1) + "…";
    }

    /** The declared source process, or 0 for "the wallet should work it out". Never throws. */
    public static long sourcePid() {
        try {
            var raw = System.getenv(SOURCE_ENV);
            if (raw == null || raw.isBlank()) return 0;
            var pid = Long.parseLong(raw.trim());
            return pid > 0 ? pid : 0;
        } catch (RuntimeException e) {
            return 0;
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
     *
     * fromAncestry() was here too, and is gone for the reason in the class comment: a walk done in the
     * client is both spoofable and blind, and the wallet can do it properly from a pid the kernel gave it.
     */
}

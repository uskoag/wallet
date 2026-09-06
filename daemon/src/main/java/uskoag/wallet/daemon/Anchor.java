package uskoag.wallet.daemon;

/**
 * The process a run of work belongs to, and therefore what a session-bound permission is bound to.
 *
 * <p>Pid and start time together, because Windows recycles pids and the pair does not. The identity is
 * used by the policy; the description is what a person reads.
 *
 * @param how     where this came from — {@code declared}, or which fallback found it. Shown, because a
 *                session that turned out to be wider than expected is otherwise impossible to explain
 * @param warning what should be said out loud about how it was reached, or null. A declaration that was
 *                not an ancestor of the calling process lands here, and it travels back to the client as
 *                well as into the log: the wallet's log is read afterwards and the client's stderr is
 *                read now
 */
public record Anchor(long pid, long startMillis, String name, String how, String warning) {

    /** Bound to nothing that outlives one call. The safe answer when the chain cannot be read at all. */
    public static Anchor unknown() {
        return new Anchor(0, 0, "(unknown)", "unresolved", null);
    }

    public boolean known() {
        return pid > 0;
    }

    public String id() {
        return known() ? "proc-" + pid + "-" + startMillis : null;
    }

    public String describe() {
        return known() ? name + " pid " + pid : "(caller not identified)";
    }

    public Anchor warn(String why) {
        return new Anchor(pid, startMillis, name, how, why);
    }
}

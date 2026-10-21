package uskoag.wallet.wire;

/**
 * One process in the chain above whoever is asking, as read from the kernel when the grant was issued.
 *
 * <p>The chain is the answer to "who is really asking": the tool that made the call is rarely what a person
 * started, and approving the tool alone means being asked again by the next command the same agent runs.
 * Approving one of its ancestors instead covers everything that ancestor starts, for as long as it lives.
 * How broad each ancestor is, and how long it may carry an approval, is worked out when the window opens.
 *
 * @param hop       0 for the calling process, then one per parent
 * @param imagePath the executable's full path, as the kernel reports it
 */
public record AncestryHop(int hop, long pid, long startMillis, String imagePath) {

    /** The same identity a session-bound rule stores: pid and start time, because pids are reused. */
    public String id() {
        return id(pid, startMillis);
    }

    public static String id(long pid, long startMillis) {
        return "proc-" + pid + "-" + startMillis;
    }
}

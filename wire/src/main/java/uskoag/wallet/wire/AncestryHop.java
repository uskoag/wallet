package uskoag.wallet.wire;

/**
 * One process in the chain above whoever is asking, as the approval window shows it.
 *
 * <p>The chain is the answer to "who is really asking": the tool that made the call is rarely what a person
 * started, and approving the tool alone means being asked again by the next command the same agent runs.
 * Approving one of its ancestors instead covers everything that ancestor starts, for as long as it lives.
 *
 * @param hop         0 for the calling process, then one per parent
 * @param descendants live processes under this one when the window opened: how much an approval anchored
 *                    here would cover, as a number a person can judge
 * @param category    what kind of process this is, in words
 * @param capMinutes  the longest an approval anchored here may stand; 0 means it cannot be picked at all,
 *                    which is what the desktop shell and a terminal hosting many tabs get
 */
public record AncestryHop(int hop, long pid, long startMillis, String image, int descendants,
                          String category, int capMinutes) {

    /** The same identity a session-bound rule stores: pid and start time, because pids are reused. */
    public String id() {
        return "proc-" + pid + "-" + startMillis;
    }

    public boolean selectable() {
        return capMinutes > 0;
    }
}

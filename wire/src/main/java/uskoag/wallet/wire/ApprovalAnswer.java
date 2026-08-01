package uskoag.wallet.wire;

/**
 * What the person chose, and how long it should stand.
 *
 * <p>{@code ops} before {@code minutes}: whichever limit is reached first stops it and re-prompts.
 * "Approved: up to 50 deletions under Shared Documents, next 20 minutes" is a sentence you can evaluate.
 * "Approved for 20 minutes" is not.
 */
public record ApprovalAnswer(boolean allowed, boolean remember, int ops, int minutes, Match match, String note) {

    public static ApprovalAnswer deny() {
        return new ApprovalAnswer(false, false, 0, 0, Match.EXACT, null);
    }

    /**
     * Nobody answered in time.
     *
     * <p>A refusal rather than a hang, and it has to arrive before the client's own read timeout, or the
     * call dies while the dialog is still open — and then a click made minutes later writes a standing
     * rule for a request that already failed, with nothing on screen to say so. A grant for a dead call
     * is the worst outcome available here: it is a permission nobody knowingly gave.
     */
    public static ApprovalAnswer timedOut(int seconds) {
        return new ApprovalAnswer(false, false, 0, 0, Match.EXACT,
                "nobody answered the wallet within " + seconds + "s, so it was refused");
    }

    /**
     * Refused before a window was ever shown, for a reason worth passing on verbatim.
     *
     * <p>Distinct from {@link #deny()}, which means a person said no. These are the cases where asking
     * would have been pointless or impossible — the wallet locked while the request waited its turn, or
     * the queue of pending approvals was never worked through — and a caller that reports "denied" for
     * those sends someone looking for a decision nobody made.
     */
    public static ApprovalAnswer denied(String why) {
        return new ApprovalAnswer(false, false, 0, 0, Match.EXACT, why);
    }

    /** One operation, nothing written down. The safe default when someone hits Enter without reading. */
    public static ApprovalAnswer once() {
        return new ApprovalAnswer(true, false, 1, 0, Match.EXACT, null);
    }

    public static ApprovalAnswer forever(Match match) {
        return new ApprovalAnswer(true, true, -1, 0, match, null);
    }

    public static ApprovalAnswer forMinutes(int minutes, int ops, Match match) {
        return new ApprovalAnswer(true, true, ops, minutes, match, null);
    }
}

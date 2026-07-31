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

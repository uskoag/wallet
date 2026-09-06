package uskoag.wallet.wire;

import java.util.List;

/**
 * The CLI's side of the policy surface, so an agent can ask "may I?" before doing the work rather than
 * discovering the answer halfway through a batch.
 */
public record PolicyRequest(
        String profile,
        String account,
        String api,
        String resource,
        Tier tier,
        Match match,
        int minutes,
        int ops,
        String reason,
        boolean thisRun) {

    /**
     * {@code thisRun} pins a blanket permission to the process this run of work belongs to, so it dies
     * when that run ends instead of standing until its clock runs out.
     *
     * <p>This is the answer to "read and write across documents and across accounts, without being asked
     * every time" that does not simply widen the door. An unpinned blanket rule is inherited by whatever
     * runs next; a pinned one cannot be, because the anchor it names will not exist. It is strictly
     * narrower than the same rule without it, and it only became possible once the wallet learned to
     * establish which process is calling rather than being told.
     */
    public PolicyRequest {
    }
}

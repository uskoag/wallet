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
        String reason) {
}

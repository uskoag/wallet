package uskoag.wallet.daemon;

import uskoag.wallet.wire.Groups;
import uskoag.wallet.wire.Needs;
import uskoag.wallet.wire.ScopeGroup;
import uskoag.wallet.wire.Tier;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which of an account's tokens serves this request.
 *
 * <p>Chosen per request rather than per tool, because a tool is not one scope set: uskoag-gslides edits
 * a deck, exports it and uploads an image, and those want three different tokens. Every proxied request
 * goes to exactly one API at exactly one tier, so the narrowest sufficient token can be picked at the
 * moment it is actually known.
 *
 * <p>Three keys, in this order, and the first one is the one that matters. A token carrying exactly what
 * the request needs always beats one that merely happens to subsume it — otherwise a Drive read would be
 * served by the full-control token, since {@code drive} satisfies a {@code drive.readonly} request, and
 * the whole point of holding a narrow token would be lost. Then the explicit order, then privilege rank.
 *
 * <p>Rank rather than scope count: counting gets it backwards, since {@code drive} is one scope that can
 * delete everything while {@code docs} is three that cannot delete a file.
 */
public final class TokenPicker {

    private TokenPicker() {
    }

    public static Optional<CredentialRecord> pick(List<CredentialRecord> tokens, String api, Tier tier) {
        var needed = Needs.forRequest(api, tier);
        var wider = Needs.alternatives(api, tier);
        return tokens.stream()
                .filter(t -> t.refreshToken != null)
                .filter(t -> t.covers(needed) || t.coversAny(wider))
                .min(Comparator.comparingInt((CredentialRecord t) -> t.covers(needed) ? 0 : 1)
                        .thenComparingInt(t -> t.order)
                        .thenComparingInt(TokenPicker::rank));
    }

    /** A group's declared privilege, or the pessimistic default for hand-written and inherited sets. */
    static int rank(CredentialRecord token) {
        return Groups.byId(token.group).map(ScopeGroup::rank).orElse(ScopeGroup.UNKNOWN_RANK);
    }

    /**
     * What to tell someone when nothing fits, in the words that say which consent to run — never a bare
     * 403, which is what the old single-token store gave you and what made this hard to diagnose.
     */
    public static String explain(String account, String api, Tier tier, List<CredentialRecord> held) {
        var needed = Needs.forRequest(api, tier);
        var groups = Groups.covering(needed);
        var suggestion = groups.isEmpty() ? "(no built-in group covers it - grant a custom scope set)"
                : groups.getFirst().id();
        return account + " has no token for " + api + "/" + tier
                + ". Needs " + String.join(", ", needed)
                + ". Holds: " + (held.isEmpty() ? "nothing" : held.stream().map(CredentialRecord::group).toList())
                + ". Run: uskoag-walletcli login " + account + " --groups " + suggestion;
    }
}

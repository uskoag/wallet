package uskoag.wallet.daemon;

import uskoag.wallet.wire.ApprovalAnswer;
import uskoag.wallet.wire.Match;
import uskoag.wallet.wire.PolicyRule;
import uskoag.wallet.wire.ResourceRef;
import uskoag.wallet.wire.Tier;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Decides, from the standing rules alone, whether a classified request may proceed.
 *
 * <p>Browsing is always free — listing folders, searching, reading a file's metadata — because gating
 * that would obstruct the 95% path for no gain: it names no document, so there is nothing to approve.
 * Everything that does name a document needs a rule, and the first touch of a new one becomes a rule
 * the moment a person says so.
 */
public final class PolicyEngine {

    private final Keyring keyring;
    private final WalletSettings settings;

    public PolicyEngine(Keyring keyring, WalletSettings settings) {
        this.keyring = keyring;
        this.settings = settings;
    }

    public Verdict decide(String profile, String account, String session, Classification c) {
        if (!keyring.unlocked()) return Verdict.DENY;

        var tier = c.tier();
        var res = c.resource();

        if (tier == Tier.READ && (res.isBrowse() || !settings.readRequiresRule)) return Verdict.ALLOW;
        if (tier == Tier.MUTATE && !settings.mutateRequiresRule) return Verdict.ALLOW;

        var rule = matching(profile, account, session, res, tier);
        if (rule == null) return Verdict.PROMPT;

        rule.opsUsed += tier == Tier.READ ? 0 : c.itemCount();
        rule.lastUsed = System.currentTimeMillis();
        return Verdict.ALLOW;
    }

    /** The live rule that covers this, or null. Expired and exhausted rules are swept as we pass them. */
    public synchronized PolicyRule matching(String profile, String account, String session,
                                            ResourceRef res, Tier tier) {
        var now = System.currentTimeMillis();
        var rules = keyring.data().rules();
        rules.removeIf(r -> r.expiresAt > 0 && now - r.expiresAt > 86_400_000L);
        for (var r : rules) {
            if (!r.live(now)) continue;
            if (!r.tier.atLeast(tier)) continue;
            if (r.applies(profile, account, res.api(), res.id(), session)) return r;
        }
        return null;
    }

    /** Turns what the person clicked into a standing rule, which is the only way a rule is ever born. */
    public synchronized PolicyRule remember(String profile, String account, String session, ResourceRef res,
                                            Tier tier, ApprovalAnswer answer, String note) throws IOException {
        var r = new PolicyRule();
        r.id = UUID.randomUUID().toString().substring(0, 8);
        r.profile = profile;
        r.account = account;
        r.api = res.api();
        r.resource = res.id();
        r.session = tier == Tier.DESTRUCTIVE ? session : null;
        r.match = answer.match() == null ? Match.EXACT : answer.match();
        r.tier = tier;
        r.opsBudget = answer.ops();
        r.expiresAt = answer.minutes() > 0 ? System.currentTimeMillis() + answer.minutes() * 60_000L : 0;
        r.createdAt = System.currentTimeMillis();
        r.note = note;
        keyring.data().rules().add(r);
        keyring.save();
        return r;
    }

    public synchronized List<PolicyRule> rules() {
        return List.copyOf(keyring.data().rules());
    }

    public synchronized boolean revoke(String id) throws IOException {
        var gone = keyring.data().rules().removeIf(r -> r.id.equals(id));
        if (gone) keyring.save();
        return gone;
    }

    public synchronized int clear() throws IOException {
        var n = keyring.data().rules().size();
        keyring.data().rules().clear();
        keyring.save();
        return n;
    }

    /** The wallet's own suggestion when it opens the dialog: counts first, then time. */
    public ApprovalAnswer defaultOffer(Tier tier) {
        return tier == Tier.DESTRUCTIVE
                ? ApprovalAnswer.forMinutes(settings.destructiveMinutes, settings.destructiveOps, Match.EXACT)
                : ApprovalAnswer.forever(Match.EXACT);
    }
}

package uskoag.wallet.daemon;

import uskoag.wallet.wire.ApprovalAnswer;
import uskoag.wallet.wire.Match;
import uskoag.wallet.wire.PolicyRule;
import uskoag.wallet.wire.ResourceRef;
import uskoag.wallet.wire.Span;
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

    /**
     * @param rule the rule that answered, when one did. Handed back so the caller can label the audit
     *             from the name recorded at approval time: a request a rule already covers never asks
     *             Google for a name, and an audit full of bare ids is unreadable exactly when it
     *             matters.
     */
    public record Ruling(Verdict verdict, PolicyRule rule) {

        static Ruling of(Verdict v) {
            return new Ruling(v, null);
        }
    }

    public Ruling decide(String profile, String account, String session, Classification c) {
        if (!keyring.unlocked()) return Ruling.of(Verdict.DENY);

        var tier = c.tier();
        var res = c.resource();

        if (tier == Tier.READ && (res.isBrowse() || !settings.readRequiresRule)) return Ruling.of(Verdict.ALLOW);
        if (tier == Tier.MUTATE && !settings.mutateRequiresRule) return Ruling.of(Verdict.ALLOW);

        var rule = matching(profile, account, session, res, tier);
        if (rule == null) return Ruling.of(Verdict.PROMPT);

        rule.opsUsed += tier == Tier.READ ? 0 : c.itemCount();
        rule.lastUsed = System.currentTimeMillis();
        return new Ruling(Verdict.ALLOW, rule);
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
        // Only ever a name the wallet actually got out of Google; null when it could not, so a listing
        // never presents a guess as the document's title.
        r.label = res.label();
        r.match = answer.match() == null ? Match.EXACT : answer.match();
        // Bound to the run in two cases, and the second one is what makes a wide rule safe to offer at
        // all. An irreversible grant has always been session-bound. A rule matching EVERY resource now
        // is too, whatever its tier: the reason someone ticks that box is a batch they are watching, and
        // a permission covering everything must not outlive the command that asked for it and be
        // inherited by whatever runs next. It still expires and, on the irreversible tier, still counts.
        r.session = tier == Tier.DESTRUCTIVE || r.match == Match.ANY ? session : null;
        r.tier = tier;
        r.opsBudget = answer.ops();
        r.expiresAt = expiryFor(tier, answer.minutes());
        r.createdAt = System.currentTimeMillis();
        r.note = note;
        keyring.data().rules().add(r);
        keyring.save();
        return r;
    }

    /**
     * When a permission of this tier runs out. Never "not at all".
     *
     * <p>Enforced here rather than only in the dialog, because the dialog is not the control: the CLI
     * reaches the same operation, and a ceiling that only one route respects is a suggestion. See
     * {@link Tier#maxMinutes} for why the ceilings are what they are and why they are not configurable.
     *
     * @param minutes what was asked for; 0 or anything past the tier's ceiling is clamped to the ceiling
     */
    public long expiryFor(Tier tier, int minutes) {
        var cap = tier.maxMinutes;
        var granted = minutes <= 0 ? cap : Math.min(minutes, cap);
        if (minutes <= 0 || minutes > cap) {
            Log.warn(tier + " permission asked for " + (minutes <= 0 ? "no expiry" : Span.describe(minutes))
                    + "; capped at " + Span.describe(cap));
        }
        return System.currentTimeMillis() + granted * 60_000L;
    }

    public synchronized List<PolicyRule> rules() {
        return List.copyOf(keyring.data().rules());
    }

    /**
     * Pushes a rule's expiry out, and refreshes its operation count with it.
     *
     * <p>Added to the existing expiry rather than measured from now, so "extend by a week" on a rule
     * with a day left gives eight days and not seven — the alternative silently shortens a rule
     * whenever someone tops it up early.
     *
     * <p>The count is reset alongside the clock because an extension that left an exhausted budget in
     * place would hand back a rule that still refuses everything, which is not what anybody means by
     * extending it. That does mean extending is a real grant, which is why the CLI route to this raises
     * the approval dialog and only the wallet's own window calls it directly.
     *
     * @param minutes 0 to remove the expiry altogether
     * @throws IOException when there is nothing sensible to do: no such rule, or a rule that already has
     *                     no expiry, where adding a span would shorten it instead of extending it
     */
    public synchronized String extend(String id, int minutes) throws IOException {
        var rule = keyring.data().rules().stream()
                .filter(r -> id != null && id.equals(r.id)).findFirst()
                .orElseThrow(() -> new IOException("no rule with id " + id));

        // The ceiling applies to extending too, or extending would simply be the way around it: top up by
        // the maximum, repeatedly, and the cap has bought nothing. So an extension can push the expiry no
        // further than the tier's ceiling measured from now — which still always buys a full fresh window,
        // just never an accumulating one.
        var now = System.currentTimeMillis();
        var tier = rule.tier == null ? Tier.READ : rule.tier;
        var ceiling = now + tier.maxMinutes * 60_000L;
        var wanted = minutes <= 0 ? ceiling : Math.max(now, rule.expiresAt) + minutes * 60_000L;
        rule.expiresAt = Math.min(wanted, ceiling);
        if (wanted > ceiling) {
            Log.warn("extension of " + tier + " rule " + id + " capped at "
                    + Span.describe(tier.maxMinutes) + " from now");
        }
        rule.opsUsed = 0;
        keyring.save();
        return rule.describe();
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

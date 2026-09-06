package uskoag.wallet.cli;

import uskoag.wallet.wire.PolicyRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Collapsing the duplicates, which is the lever that actually shortens this listing.
 *
 * <p>Seven rules for one spreadsheet and five for another, all at the same tier on the same account,
 * differing only in a session id: that is a derived session changing per invocation, each one earning its
 * own rule. Rendering the JSON as lines would have printed thirty-five of them. Collapsing prints a dozen.
 *
 * <p>The duplicates are a symptom and this file is not the cure — the cure is in the policy engine and
 * belongs to another PRP. Hiding them here is worth doing anyway, because until then every listing carries
 * them and the wide rule that matters gets buried among near-identical narrow ones.
 */
public final class RuleGroups {

    /**
     * Widest first, because the listing is read to decide what to revoke. A blanket MUTATE rule sitting
     * between two READ rules on named documents is the one failure this view exists to prevent.
     */
    static final Comparator<PolicyRule> WIDEST = Comparator
            .comparingInt((PolicyRule r) -> r.blanket() ? 0 : 1)
            .thenComparingInt(r -> -r.tier.ordinal())
            .thenComparingLong(RuleGroups::ends);

    /** Within a group, the one still covering you comes first — see {@link #of} for why that matters. */
    static final Comparator<PolicyRule> LATEST_FIRST =
            Comparator.comparingLong(RuleGroups::ends).reversed();

    private RuleGroups() {
    }

    /**
     * Groups of identical scope, each ordered so its first element is the one that will outlive the rest.
     *
     * <p>That representative's id is what gets printed, and it is the honest choice: it is the rule still
     * standing after the others lapse. It does mean {@code policy revoke} on that id drops one of seven,
     * which is why the count is a visible column and why {@code --expand} exists — a reader who has to
     * revoke the whole scope needs every id, and inventing a {@code revoke --group} verb to hide the
     * problem would grow the mutating surface inside a change about printing.
     */
    static List<List<PolicyRule>> of(List<PolicyRule> rules, boolean expand) {
        if (expand) return rules.stream().sorted(WIDEST).map(List::<PolicyRule>of).toList();
        var by = new LinkedHashMap<String, List<PolicyRule>>();
        for (var r : rules) by.computeIfAbsent(key(r), k -> new ArrayList<>()).add(r);
        var out = new ArrayList<>(by.values());
        out.forEach(g -> g.sort(LATEST_FIRST));
        out.sort(Comparator.comparing(List::getFirst, WIDEST));
        return List.copyOf(out);
    }

    /**
     * What makes two rules the same permission: tier, match mode, and the four fields the engine matches
     * on. Never the id, never the session, never the expiry — those are exactly the fields the duplicates
     * differ in, and keying on any of them would collapse nothing.
     *
     * <p>The profile is in the key even though the duplicates share it. Two rules differing only by profile
     * are genuinely different scopes, and folding a gdrive rule into a gsheets one would misreport what is
     * standing — a listing may summarise, but it may not merge two permissions into one row.
     */
    private static String key(PolicyRule r) {
        return String.join("|", String.valueOf(r.tier), String.valueOf(r.match), nz(r.profile),
                nz(r.account), nz(r.api), nz(r.resource));
    }

    /** No expiry sorts last rather than first, which a raw 0 would do. */
    private static long ends(PolicyRule r) {
        return r.expiresAt <= 0 ? Long.MAX_VALUE : r.expiresAt;
    }

    private static String nz(String s) {
        return s == null ? "*" : s;
    }
}

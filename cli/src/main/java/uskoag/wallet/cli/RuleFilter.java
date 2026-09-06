package uskoag.wallet.cli;

import uskoag.wallet.wire.PolicyRule;

import java.util.List;

/**
 * The query half of {@code policy list}: which rules the reader asked about.
 *
 * <p>Client-side, deliberately. There are a dozen rules, not a million, and moving these predicates behind
 * the socket verb would put presentation logic in the engine and give the daemon a second opinion about
 * what a listing means.
 */
public final class RuleFilter {

    private RuleFilter() {
    }

    static List<PolicyRule> select(List<PolicyRule> rules, Args a) {
        var asked = a.get("tier", null);
        var tier = PolicyCommands.tier(asked);
        if (asked != null && tier == null) {
            System.err.println("--tier '" + asked + "' is not a tier. Use read, write or destructive.");
            System.exit(2);
        }
        var api = a.get("api", null);
        var account = a.get("account", null);
        var profile = a.get("profile", null);
        var needle = a.get("resource", null);
        return rules.stream()
                // atLeast, not equality: MUTATE outranks READ everywhere else in the wallet, so --tier write
                // showing a DESTRUCTIVE rule is the same reading the engine uses. A listing that answered
                // "what can write here" with only the MUTATE rules would be lying by omission.
                .filter(r -> tier == null || r.tier.atLeast(tier))
                .filter(r -> api == null || api.equalsIgnoreCase(r.api))
                .filter(r -> account == null || account.equalsIgnoreCase(r.account))
                .filter(r -> profile == null || profile.equalsIgnoreCase(r.profile))
                .filter(r -> !a.has("blanket") || r.blanket())
                .filter(r -> matches(r, needle))
                .toList();
    }

    /**
     * Substring, against the id and the label both, case-insensitively. Nobody types a full Drive id, and
     * the reason anyone reaches for this flag is that they remember what the document was called.
     */
    private static boolean matches(PolicyRule r, String needle) {
        if (needle == null) return true;
        var n = needle.toLowerCase();
        return (r.resource != null && r.resource.toLowerCase().contains(n))
                || (r.label != null && r.label.toLowerCase().contains(n));
    }
}

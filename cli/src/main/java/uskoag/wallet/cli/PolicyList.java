package uskoag.wallet.cli;

import uskoag.wallet.wire.Json;
import uskoag.wallet.wire.Match;
import uskoag.wallet.wire.PolicyReply;
import uskoag.wallet.wire.PolicyRule;
import uskoag.wallet.wire.Span;
import uskoag.wallet.wire.Tier;
import uskoag.wallet.wire.WalletClient;

import java.util.List;
import java.util.Map;

import static uskoag.wallet.cli.Render.plural;

/**
 * {@code policy list} as lines a person can scan.
 *
 * <p>It printed the daemon's reply verbatim, which for thirty-five rules is one unwrapped JSON line — so
 * {@code | tail -15} came back as a mangled fragment, because there was only ever one line to tail. Three
 * separate faults sat underneath that, and each needs a different answer: rendering for the JSON,
 * {@link RuleGroups} for the duplicate rules a per-invocation session id manufactures, and hiding the dead
 * for the expired rules this listing has been presenting as standing permissions —
 * {@code PolicyEngine.matching} sweeps only what is more than a day dead, and only when something calls it.
 *
 * <p>{@code --json} is an untouched passthrough of the daemon's reply, which is the compatibility guarantee
 * that makes changing the default safe at all.
 */
public final class PolicyList {

    private PolicyList() {
    }

    static int run(WalletClient client, Args a) throws Exception {
        var raw = client.callRaw("policy.list", Map.of());
        if (a.has("json")) return WalletCli.out(raw);
        var reply = Json.to(raw, PolicyReply.class);
        if (reply == null || reply.rules() == null) return WalletCli.out(raw);
        reply.rules().forEach(PolicyList::normalise);

        var chosen = RuleFilter.select(reply.rules(), a);
        if (a.has("tsv")) return tsv(chosen);
        if (chosen.isEmpty()) return nothing(reply.rules().size());

        var now = System.currentTimeMillis();
        var live = chosen.stream().filter(r -> r.live(now)).toList();
        var dead = chosen.stream().filter(r -> !r.live(now)).toList();
        // Only when it carries information. One account is the common case and a column of the same value
        // repeated twelve times is width spent on nothing; two accounts and it becomes the fact that decides
        // whether a rule is yours to worry about.
        var showAccount = chosen.stream().map(r -> r.account).distinct().count() > 1;

        section(head(live.size(), dead.size(), a.has("all")), live, now, showAccount, a);
        if (a.has("all") && !dead.isEmpty()) {
            section("EXPIRED OR SPENT  (" + plural(dead.size(), "rule") + ")", dead, now, showAccount, a);
        }
        footer(a.has("all") ? chosen : live, a);
        return 0;
    }

    /**
     * Gson writes a JSON null straight onto the field, past the initialiser that would have made it READ. A
     * null tier then throws inside a comparator, in the one command someone runs when they are already
     * trying to work out what is standing.
     */
    private static void normalise(PolicyRule r) {
        if (r.tier == null) r.tier = Tier.READ;
        if (r.match == null) r.match = Match.EXACT;
    }

    private static String head(int live, int dead, boolean all) {
        var s = "LIVE  (" + plural(live, "rule");
        return dead == 0 || all ? s + ")" : s + ", " + dead + " expired or spent — --all to see them)";
    }

    private static void section(String title, List<PolicyRule> rules, long now, boolean showAccount, Args a) {
        System.out.println();
        System.out.println(title);
        System.out.println();
        for (var group : RuleGroups.of(rules, a.has("expand"))) {
            System.out.println(RuleRow.of(group, now, showAccount));
        }
    }

    /**
     * The two things a reader of this listing needs told and cannot infer: that a count column means rules
     * were merged, and which flags narrow it. Both conditional on being useful — the collapse note only when
     * something actually collapsed.
     */
    private static void footer(List<PolicyRule> shown, Args a) {
        System.out.println();
        if (!a.has("expand") && RuleGroups.of(shown, false).stream().anyMatch(g -> g.size() > 1)) {
            System.out.println("  xN collapses N rules of identical scope; the id shown is the one that"
                    + " outlives the rest — --expand for one line each");
        }
        System.out.println("  filters: --tier write  --api sheets  --account e  --resource <text>"
                + "  --blanket  --all  --expand  --tsv  --json");
    }

    /** Distinguishes an empty keyring from a filter that matched nothing; they call for different next moves. */
    private static int nothing(int total) {
        if (total > 0) {
            System.out.println("None of the " + plural(total, "standing rule") + " match that filter.");
            return 0;
        }
        System.out.println("No standing permissions.");
        System.out.println("  uskoag-walletcli policy allow --api sheets --resource <id> --tier write");
        System.out.println("  uskoag-walletcli policy quiet --tier write     (everything, for "
                + Span.describe(Tier.MUTATE.blanketMaxMinutes) + ")");
        return 0;
    }

    /**
     * Full ids, full labels, nothing collapsed, and the dead rules included. A sheet wants rows and has its
     * own filters; every column that the listing decides for the reader is one this hands over instead.
     */
    private static int tsv(List<PolicyRule> rules) {
        System.out.println(PolicyRule.tsvHeader());
        rules.forEach(r -> System.out.println(r.tsv()));
        return 0;
    }
}

package uskoag.wallet.cli;

import uskoag.wallet.wire.Match;
import uskoag.wallet.wire.PolicyRequest;
import uskoag.wallet.wire.Span;
import uskoag.wallet.wire.Tier;
import uskoag.wallet.wire.WalletClient;

import java.util.Map;

/**
 * The standing-permission surface, so an agent can settle "may I?" before starting a batch.
 *
 * <p>{@code allow} asks; it does not award. A process able to grant itself a rule would have made the
 * whole thing decorative, so this raises the same dialog a first touch would have raised.
 */
public final class PolicyCommands {

    private PolicyCommands() {
    }

    public static int run(WalletClient client, Args a) throws Exception {
        var sub = a.at(1) == null ? "list" : a.at(1);
        return switch (sub) {
            case "list" -> WalletCli.out(client.callRaw("policy.list", Map.of()));
            case "clear" -> WalletCli.out(client.callRaw("policy.clear", Map.of()));
            case "revoke" -> WalletCli.out(client.callRaw("policy.revoke", ask(a, a.at(2))));
            case "check" -> WalletCli.out(client.callRaw("policy.check", ask(a, a.get("resource", a.at(2)))));
            case "allow" -> WalletCli.out(client.callRaw("policy.allow", ask(a, a.get("resource", a.at(2)))));
            case "quiet" -> quiet(client, a);
            case "extend" -> extend(client, a);
            default -> {
                System.err.println("usage: uskoag-walletcli policy list|check|allow|quiet|extend|revoke|clear");
                yield 1;
            }
        };
    }

    /**
     * {@code policy quiet --tier read|write}. One rule covering every document, for the tier's ceiling.
     *
     * <p>The defaults are wide on purpose — every account, every API — because the reason anyone runs this
     * is that they do not want to think about which documents a batch will touch. Narrowing with
     * {@code --account} or {@code --api} is offered and is better hygiene where the answer is known.
     *
     * <p>{@code --tier write} covers reading too, since MUTATE outranks READ, so heavy automation needs
     * one of these and not two.
     *
     * <p>The ceiling here is the BLANKET one and it is shorter than the tier's ordinary ceiling: 24 hours
     * to read anything, 1 hour to change anything, against a week and a day for a rule about one named
     * document. Same grant, same ceiling, from the wallet's tray window — the two are one mechanism.
     */
    private static int quiet(WalletClient client, Args a) throws Exception {
        var tier = a.get("tier", null);
        if (tier == null) {
            System.err.println("usage: uskoag-walletcli policy quiet --tier read|write [--account e]"
                    + " [--api sheets|drive|gmail|slides] [--minutes N]");
            System.err.println("  --tier write covers read as well. Asks for the passphrase, because this"
                    + " covers documents nobody has named.");
            System.err.println("  Capped at " + Span.describe(Tier.READ.blanketMaxMinutes) + " for read and "
                    + Span.describe(Tier.MUTATE.blanketMaxMinutes) + " for write — shorter than a rule"
                    + " about one named document, because this one names none.");
            System.err.println("  The same thing with a window instead: the wallet's tray icon →"
                    + " \"Open access for a while…\".");
            return 1;
        }
        var t = tier(tier);
        if (t == null) {
            System.err.println("--tier '" + tier + "' is not a tier. Use read or write.");
            return 1;
        }
        return WalletCli.out(client.callRaw("policy.quiet", new PolicyRequest(
                null, a.get("account", null), a.get("api", null), null,
                t, Match.EXACT, a.num("minutes", 0), a.num("ops", 0), a.get("reason", null))));
    }

    /**
     * {@code policy extend <ruleId> --by 1w}. The span is refused rather than guessed: reading an
     * unparseable {@code --by} as "forever" would turn a typo into a permanent grant.
     */
    private static int extend(WalletClient client, Args a) throws Exception {
        var id = a.at(2);
        if (id == null) {
            System.err.println("usage: uskoag-walletcli policy extend <ruleId> --by 1h|1d|1w|1mo|forever");
            return 1;
        }
        var by = a.get("by", "1w");
        var minutes = Span.parse(by);
        if (minutes == null) {
            System.err.println("--by '" + by + "' is not a span. Try 45m, 12h, 10d, 3w, 6mo, 2y, or forever.");
            return 1;
        }
        var t = tier(a.get("tier", "read"));
        if (t == null) {
            System.err.println("--tier '" + a.get("tier", "read")
                    + "' is not a tier. Use read, write or destructive.");
            return 1;
        }
        return WalletCli.out(client.callRaw("policy.extend", new PolicyRequest(
                a.get("profile", "*"), a.get("account", "*"), a.get("api", "drive"), id, t,
                Match.valueOf(a.get("match", "exact").toUpperCase()),
                minutes, a.num("ops", 0), a.get("reason", null))));
    }

    /**
     * A tier from what a person would actually type, or null.
     *
     * <p>{@code Tier.valueOf} was called directly here, so {@code --tier write} died with
     * {@code No enum constant uskoag.wallet.wire.Tier.WRITE} — while {@code --tier write} was what
     * {@code walletcli --help} recommended for {@code check}, {@code allow} and {@code quiet}, what both
     * client tools' help recommended, and what the global instructions told a fresh agent to run. Five
     * documented incantations, none of which worked, all of them failing at exactly the moment someone was
     * trying to set permissions up BEFORE a batch — the one thing the documentation insists you get right.
     *
     * <p>Found by a sub-agent that had only the help text to go on, which is the only way it would have been
     * found: everyone who knew the tier was called MUTATE typed MUTATE.
     */
    static Tier tier(String s) {
        if (s == null) return null;
        return switch (s.trim().toLowerCase()) {
            case "read" -> Tier.READ;
            case "write", "mutate", "change" -> Tier.MUTATE;
            case "destructive", "delete", "irreversible" -> Tier.DESTRUCTIVE;
            default -> null;
        };
    }

    private static PolicyRequest ask(Args a, String resource) {
        var t = tier(a.get("tier", "read"));
        if (t == null) {
            System.err.println("--tier '" + a.get("tier", "read")
                    + "' is not a tier. Use read, write or destructive.");
            System.exit(2);
        }
        return new PolicyRequest(
                a.get("profile", "*"),
                a.get("account", "*"),
                a.get("api", "drive"),
                resource,
                t,
                Match.valueOf(a.get("match", "exact").toUpperCase()),
                a.num("minutes", 0),
                a.num("ops", 0),
                a.get("reason", null));
    }
}

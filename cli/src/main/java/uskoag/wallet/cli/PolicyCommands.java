package uskoag.wallet.cli;

import uskoag.wallet.wire.Match;
import uskoag.wallet.wire.PolicyRequest;
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
            default -> {
                System.err.println("usage: uskoag-walletcli policy list|check|allow|revoke|clear");
                yield 1;
            }
        };
    }

    private static PolicyRequest ask(Args a, String resource) {
        return new PolicyRequest(
                a.get("profile", "*"),
                a.get("account", "*"),
                a.get("api", "drive"),
                resource,
                Tier.valueOf(a.get("tier", "read").toUpperCase()),
                Match.valueOf(a.get("match", "exact").toUpperCase()),
                a.num("minutes", 0),
                a.num("ops", 0),
                a.get("reason", null));
    }
}

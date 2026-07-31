package uskoag.wallet.cli;

import uskoag.wallet.wire.AccountInfo;
import uskoag.wallet.wire.Asks;
import uskoag.wallet.wire.Groups;
import uskoag.wallet.wire.Json;
import uskoag.wallet.wire.TokenInfo;
import uskoag.wallet.wire.WalletClient;

import java.util.Map;

/** Listing, removing and reordering an account's tokens from a terminal. */
public final class TokenCommands {

    private TokenCommands() {
    }

    public static int run(WalletClient client, Args a) throws Exception {
        var sub = a.at(1) == null ? "list" : a.at(1);
        return switch (sub) {
            case "list" -> list(client, a);
            case "remove" -> WalletCli.out(client.callRaw("token.remove",
                    new Asks.TokenRef(a.at(2), a.get("group", a.at(3)))));
            case "reorder" -> WalletCli.out(client.callRaw("token.reorder",
                    new Asks.Reorder(a.at(2), a.list("groups"))));
            case "unused" -> WalletCli.out(client.callRaw("token.unused", new Asks.Unused(a.num("days", 0))));
            case "remove-unused" -> WalletCli.out(client.callRaw("token.removeUnused",
                    new Asks.Unused(a.num("days", 0))));
            default -> {
                System.err.println("usage: uskoag-walletcli token list [--tsv] | remove <email> --group <g>"
                        + " | reorder <email> --groups a,b | unused [--days 90] | remove-unused [--days 90]");
                yield 1;
            }
        };
    }

    /** Human by default, TSV on request, so an inventory pastes straight into a sheet. */
    private static int list(WalletClient client, Args a) throws Exception {
        var raw = client.callRaw("token.list", Map.of());
        if (a.has("json")) return WalletCli.out(raw);

        var accounts = Json.GSON.fromJson(raw, AccountInfo[].class);
        if (a.has("tsv")) {
            System.out.println(TokenInfo.tsvHeader());
            for (var acc : accounts) for (var t : acc.tokens()) System.out.println(t.tsv());
            return 0;
        }
        for (var acc : accounts) {
            System.out.println(acc.email() + "   [" + acc.org() + "]");
            if (!acc.hasAnyToken()) {
                System.out.println("      (no tokens - run: uskoag-walletcli login " + acc.email()
                        + " --groups " + Groups.DOCS.id() + ")");
                continue;
            }
            for (var t : acc.tokens()) {
                System.out.printf("   %2d. %-38s %-12s used %6s   last %s%n",
                        t.order(), t.label(), t.tier().label, TokenInfo.count(t.useCount()),
                        t.lastUsed() == 0 ? "never" : java.time.Instant.ofEpochMilli(t.lastUsed()).toString());
                if (a.has("scopes")) t.scopes().forEach(s -> System.out.println("         " + s));
            }
        }
        return 0;
    }
}

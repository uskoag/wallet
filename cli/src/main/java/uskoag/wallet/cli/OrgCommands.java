package uskoag.wallet.cli;

import uskoag.wallet.wire.Asks;
import uskoag.wallet.wire.WalletClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** The OAuth clients, from a terminal. One credentials.json per Cloud project. */
public final class OrgCommands {

    private OrgCommands() {
    }

    public static int run(WalletClient client, Args a) throws Exception {
        var sub = a.at(1) == null ? "list" : a.at(1);
        return switch (sub) {
            case "list" -> WalletCli.out(client.callRaw("orgs", Map.of()));
            case "add" -> add(client, a);
            case "domains" -> WalletCli.out(client.callRaw("org.domains",
                    new Asks.OrgDomains(a.at(2), a.list("domains"))));
            case "remove" -> WalletCli.out(client.callRaw("org.remove", new Asks.OrgRef(a.at(2))));
            case "rename" -> WalletCli.out(client.callRaw("org.rename",
                    new Asks.OrgRename(a.at(2), a.at(3))));
            default -> {
                usage();
                yield 1;
            }
        };
    }

    private static int add(WalletClient client, Args a) throws Exception {
        var id = a.at(2);
        var file = a.get("file", null);
        if (id == null || file == null) {
            usage();
            return 1;
        }
        return WalletCli.out(client.callRaw("org.add", new Asks.AddOrg(id, a.get("label", id),
                Files.readString(Path.of(file)), a.list("domains"))));
    }

    private static void usage() {
        System.err.println("usage: uskoag-walletcli org list");
        System.err.println("       uskoag-walletcli org add <id> --file <credentials.json>"
                + " [--label \"...\"] [--domains a.org,*.b.org]");
        System.err.println("       uskoag-walletcli org domains <id> --domains a.org,*.b.org,re:PATTERN");
        System.err.println("       uskoag-walletcli org rename <old-id> <new-id>");
        System.err.println("       uskoag-walletcli org remove <id>");
        System.err.println();
        System.err.println("domain patterns: exact  |  *.wildcard  |  re:regex   (a client may serve many)");
    }
}

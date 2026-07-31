package uskoag.wallet.cli;

import uskoag.wallet.wire.Asks;
import uskoag.wallet.wire.Groups;
import uskoag.wallet.wire.Profiles;
import uskoag.wallet.wire.ScopeGroup;
import uskoag.wallet.wire.WalletClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Orgs, consent, migration and export from a terminal. */
public final class AccountCommands {

    private AccountCommands() {
    }

    /**
     * {@code org add <id> --file credentials.json [--domains a.org,b.org] [--label "..."]}
     *
     * <p>Per org, not per tool and not per account: a credentials.json is a Cloud project's OAuth
     * client. One per org is also what an unverified app leaves available, given the hundred-user cap
     * and a Workspace admin's power to refuse a foreign client ID.
     */
    public static int addOrg(WalletClient client, Args a) throws Exception {
        var id = a.at(2);
        var file = a.get("file", null);
        if (id == null || file == null) {
            System.err.println("usage: uskoag-walletcli org add <id> --file <credentials.json>"
                    + " [--domains a.org,b.org] [--label \"...\"]");
            return 1;
        }
        return WalletCli.out(client.callRaw("org.add", new Asks.AddOrg(id, a.get("label", id),
                Files.readString(Path.of(file)), a.list("domains"))));
    }

    /**
     * Consent for one account, requesting every tool's scopes at once by default.
     *
     * <p>One browser round trip instead of one per tool, and it sidesteps the trap in the old store:
     * tokens were keyed by app-key and never by scopes, so the second tool silently reused the first
     * one's narrower token and failed later with an opaque 403.
     */
    public static int login(WalletClient client, Args a) throws Exception {
        var email = a.at(1);
        if (email == null) {
            System.err.println("usage: uskoag-walletcli login <email> [--org <id>]");
            System.err.println("         --groups docs,drive.read,...   one consent screen per group");
            System.err.println("         --groups gsheets               a tool name expands to its groups");
            System.err.println("         --scopes <url,url> [--custom-id name]   hand-written set");
            System.err.println();
            System.err.println("groups:  " + String.join(", ", Groups.all().stream().map(ScopeGroup::id).toList()));
            System.err.println("tools:   " + String.join(", ", Profiles.known()));
            return 1;
        }
        var groups = a.list("groups");
        var scopes = a.list("scopes");
        if (groups.isEmpty() && scopes.isEmpty()) {
            System.err.println("nothing to grant. Pass --groups or --scopes; see 'login' with no email for the list.");
            return 1;
        }
        var screens = groups.size() + (scopes.isEmpty() ? 0 : 1);
        System.err.println(screens + " consent screen(s) will open, one per group. Each becomes its own"
                + " token with its own expiry.");
        return WalletCli.out(client.callRaw("login", new Asks.Login(email, a.get("org", null), groups,
                a.get("custom-id", "custom"), scopes, a.num("port", 8888))));
    }

    /**
     * The migration that replaces rotation: one old app-key, typed once and never passed as a flag,
     * moves every account it can decrypt into the keyring without a single re-consent.
     *
     * <p>Every profile by default, because one account's tokens are scattered across one directory per
     * tool and they all belong to the same account. They merge into one record with the union of
     * whatever each had granted.
     */
    public static int importOld(WalletClient client, Args a) throws Exception {
        var org = a.get("org", null);
        if (org == null) {
            System.err.println("usage: uskoag-walletcli import --org <id> [--profiles gsheets,gmail]"
                    + " [--accounts a@x,b@x] [--root <dir>] [--delete-old]");
            System.err.println("--profiles defaults to all of: " + String.join(", ", Profiles.known()));
            return 1;
        }
        var appKey = a.secret("old app-key to import with");
        if (appKey == null) {
            System.err.println("No console available. The old app-key is a secret and is never taken as a flag.");
            return 3;
        }
        var profiles = a.list("profiles").isEmpty() ? Profiles.known() : a.list("profiles");
        System.err.println("scanning " + profiles.size() + " tool store(s): " + String.join(", ", profiles));

        var reply = client.callRaw("import", new Asks.Import(org, appKey, profiles,
                a.list("accounts"), a.get("root", null), a.has("delete-old")));
        System.out.println(reply);
        if (a.has("delete-old")) {
            System.err.println("Old token stores deleted. That app-key now decrypts nothing,"
                    + " so wherever it has leaked it protects nothing.");
        } else {
            System.err.println("Old token stores kept. Re-run with --delete-old once you have verified"
                    + " the accounts work, and the leaked app-key stops mattering.");
        }
        return 0;
    }

    public static int export(WalletClient client, Args a) throws Exception {
        var email = a.at(1);
        if (email == null) {
            System.err.println("usage: uskoag-walletcli export <email> [--raw]");
            return 1;
        }
        if (a.has("raw")) {
            System.err.println("EXPORTING A RAW CREDENTIAL - refresh token and client secret."
                    + " This is recorded in the audit.");
        }
        return WalletCli.out(client.callRaw("export", new Asks.Export(email, a.has("raw"))));
    }

    public static int forget(WalletClient client, Args a) throws Exception {
        var email = a.at(1);
        if (email == null) {
            System.err.println("usage: uskoag-walletcli forget <email>");
            return 1;
        }
        return WalletCli.out(client.callRaw("forget", new Asks.Forget(email)));
    }

    static List<String> nothing() {
        return List.of();
    }
}

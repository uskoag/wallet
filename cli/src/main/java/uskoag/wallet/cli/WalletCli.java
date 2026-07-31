package uskoag.wallet.cli;

import uskoag.wallet.wire.Asks;
import uskoag.wallet.wire.Json;
import uskoag.wallet.wire.Profiles;
import uskoag.wallet.wire.WalletClient;
import uskoag.wallet.wire.WalletLauncher;

import java.util.Map;

/**
 * uskoag-walletcli — everything the window can do, scriptable.
 *
 * <p>Secrets are never flags. A passphrase or an old app-key is read from a hidden console prompt, so
 * nothing here can land in PSReadLine history or an agent transcript, which is the accidental-disclosure
 * path this whole design exists to close.
 */
public final class WalletCli {

    public static void main(String[] argv) {
        // Agents capture stdout and decode it as UTF-8; the JVM default on Windows is cp1252.
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err),
                true, java.nio.charset.StandardCharsets.UTF_8));
        var args = new Args(argv);
        try {
            System.exit(run(args));
        } catch (Exception e) {
            System.err.println("wallet: " + e.getMessage());
            System.exit(2);
        }
    }

    private static int run(Args a) throws Exception {
        var verb = a.verb();
        if ("help".equals(verb) || a.has("help")) {
            Help.print();
            return 0;
        }
        var client = WalletClient.ifRunning().orElseGet(() -> WalletLauncher.ensureRunning().orElse(null));
        if (client == null) {
            System.err.println("No wallet is running and none could be started.");
            System.err.println("Start uskoag-wallet, or set UKAG_WALLET_JAR to the shaded jar.");
            return 3;
        }

        return switch (verb) {
            case "status" -> out(client.callRaw("status", Map.of()));
            case "accounts" -> out(client.callRaw("accounts", Map.of()));
            case "orgs" -> out(client.callRaw("orgs", Map.of()));
            case "org" -> OrgCommands.run(client, a);
            case "profiles" -> out(client.callRaw("profiles", Map.of()));
            case "groups" -> out(client.callRaw("groups", Map.of()));
            case "token", "tokens" -> TokenCommands.run(client, a);
            case "audit" -> out(client.callRaw("audit", new Asks.Recent(a.num("limit", 100))));
            case "lock" -> out(client.callRaw("lock", Map.of()));
            case "backups" -> out(client.callRaw("backups", Map.of()));
            case "purgebackups" -> out(client.callRaw("purgebackups", Map.of()));
            case "reset" -> reset(client, a);
            case "shutdown" -> out(client.callRaw("shutdown", Map.of()));
            case "unlock" -> unlock(client, a);
            case "passwd" -> passwd(client, a);
            case "login" -> AccountCommands.login(client, a);
            case "import" -> AccountCommands.importOld(client, a);
            case "export" -> AccountCommands.export(client, a);
            case "forget" -> AccountCommands.forget(client, a);
            case "policy" -> PolicyCommands.run(client, a);
            default -> {
                System.err.println("unknown verb: " + verb);
                Help.print();
                yield 1;
            }
        };
    }

    private static int unlock(WalletClient client, Args a) throws Exception {
        var phrase = a.secret("wallet passphrase");
        if (phrase == null) {
            System.err.println("No console to type into. Unlock from the wallet window,"
                    + " or pipe the passphrase and pass --stdin.");
            return 3;
        }
        return out(client.callRaw("unlock", new Asks.Unlock(phrase)));
    }

    private static int passwd(WalletClient client, Args a) throws Exception {
        var current = Args.fromConsole("current passphrase");
        var fresh = Args.fromConsole("new passphrase");
        var again = Args.fromConsole("new passphrase again");
        if (current == null || fresh == null || !fresh.equals(again)) {
            System.err.println("Not changed — no console, or the two new passphrases differed.");
            return 3;
        }
        return out(client.callRaw("passwd", new Asks.Passwd(current, fresh)));
    }

    /** Prints the wallet's JSON verbatim, and maps a {@code Done} reply's own ok flag onto the exit code. */
    /**
     * The passphrase is gone. Confirmed by typing a word rather than a flag, because {@code --yes} in a
     * script is exactly how this gets done by accident.
     */
    private static int reset(WalletClient client, Args a) throws Exception {
        System.err.println("This moves the current keyring aside and starts a new one.");
        System.err.println("Lost: every refresh token (each account consents again), the standing");
        System.err.println("permissions, and the audit's detail columns.");
        System.err.println("Kept: every backed-up credentials.json, and the old keyring file itself.");
        var typed = Args.fromConsole("type RESET to confirm");
        if (!"RESET".equals(typed)) {
            System.err.println("Not reset. Use the wallet window if you have no console here.");
            return 3;
        }
        return out(client.callRaw("reset", Map.of()));
    }

    static int out(String json) {
        System.out.println(json);
        if (json == null || !json.stripLeading().startsWith("{")) return 0;
        try {
            var done = Json.to(json, Asks.Done.class);
            return done != null && done.message() != null && !done.ok() ? 1 : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private WalletCli() {
    }
}

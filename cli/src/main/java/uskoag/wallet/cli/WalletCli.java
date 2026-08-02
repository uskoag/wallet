package uskoag.wallet.cli;

import uskoag.wallet.wire.Asks;
import uskoag.wallet.wire.Json;
import uskoag.wallet.wire.Profiles;
import uskoag.wallet.wire.SessionId;
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
        // Before the client, deliberately: this answers a question about THIS process and needs no wallet,
        // and the moment you most want it is when something is behaving oddly.
        if ("session".equals(verb)) return session();
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

    /**
     * What session this invocation would present, and whether that is stable across invocations.
     *
     * <p>Exists because the instability was invisible and cost real time. A session-bound rule — which is
     * what the approval dialog's breadth box and every irreversible grant produce — covers exactly the
     * invocations that share a session, and when nothing exports one it is derived from process ancestry.
     * That walk stops at the first parent the JDK cannot see, so whether it lands on a long-lived shell or
     * on a process that dies with the command is a property of how the tool happened to be launched. Six
     * identical approvals in two and a half minutes is what that looks like from the keyboard, and there
     * was no way to see why short of reading the audit and comparing hex strings.
     */
    private static int session() {
        var env = System.getenv(SessionId.ENV);
        var explicit = env != null && !env.isBlank();
        var id = SessionId.current();
        System.out.println("session: " + id);
        System.out.println("source : " + (explicit ? SessionId.ENV : "derived from process ancestry"));
        if (explicit) {
            System.out.println();
            System.out.println("Stable. Every tool launched with this variable set presents the same session,");
            System.out.println("so one approval covers the whole run.");
            return 0;
        }
        System.out.println("ancestry: " + chain());
        System.out.println();
        System.out.println("Derived, so it is whatever that walk happened to reach, and it fails in BOTH");
        System.out.println("directions. Stopping early gives a session that dies with this one command, so");
        System.out.println("every session-bound permission — the breadth box, and every irreversible grant —");
        System.out.println("is asked again next time. Reaching explorer.exe gives the opposite: one session");
        System.out.println("shared by every process in this Windows login, so a grant said to end with the");
        System.out.println("command actually stands until it expires. Neither is 'a run of work'.");
        System.out.println();
        System.out.println("To make it stable, export a name for the run before the first tool call:");
        System.out.println("  PowerShell   $env:" + SessionId.ENV + " = \"invoice reconciliation, March\"");
        System.out.println("  bash         export " + SessionId.ENV + "=\"invoice reconciliation, March\"");
        System.out.println();
        System.out.println("Words, not a hex string: this is what the approval dialog and the audit show,");
        System.out.println("and it is a correlation key, never an authorisation boundary.");
        System.out.println();
        System.out.println("To stop being asked at all, that is a different question and the answer is");
        System.out.println("  uskoag-walletcli policy quiet --tier write");
        return 0;
    }

    /**
     * The parent chain as names, which is the one thing that makes a derived session interpretable. Uses
     * the same walk {@link SessionId#fromAncestry} does, so what is printed is what was keyed on.
     */
    private static String chain() {
        var parts = new java.util.ArrayList<String>();
        try {
            for (var p = ProcessHandle.current(); ; ) {
                parts.add(p.pid() + ":" + p.info().command()
                        .map(c -> c.substring(c.replace('\\', '/').lastIndexOf('/') + 1)).orElse("?"));
                var parent = p.parent();
                if (parent.isEmpty() || parts.size() > 20) break;
                p = parent.get();
            }
        } catch (Exception e) {
            parts.add("(walk failed: " + e.getMessage() + ")");
        }
        return String.join(" <- ", parts);
    }

    private static int unlock(WalletClient client, Args a) throws Exception {
        var phrase = a.secret("wallet passphrase");
        if (phrase == null) {
            // No console to read a hidden passphrase from — but the wallet has a window, and refusing while
            // being perfectly able to ask is a dead end for exactly the callers who hit this: an agent's
            // shell, a scheduled task, `! uskoag-walletcli unlock` typed into claude-code. Naming the
            // remedy is not the same as offering it, so raise the wallet's own unlock window and say so.
            System.err.println("No console to type into, so the wallet's unlock window has been raised on"
                    + " screen — type the passphrase there.");
            System.err.println("(To script it instead, pipe the passphrase in and pass --stdin.)");
            try {
                client.callRaw("show", Map.of());
            } catch (Exception e) {
                System.err.println("...except the window could not be raised: " + e.getMessage());
            }
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

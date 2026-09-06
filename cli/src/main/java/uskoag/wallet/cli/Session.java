package uskoag.wallet.cli;

import uskoag.wallet.wire.AccessRequest;
import uskoag.wallet.wire.SessionId;
import uskoag.wallet.wire.SessionReport;
import uskoag.wallet.wire.WalletClient;

/**
 * {@code uskoag-walletcli session} — which run of work the wallet thinks this is, and why.
 *
 * <p>Run it when you are being asked the same question twice.
 *
 * <p>It asks the wallet rather than working it out here, and that is the substance of the change. The
 * old version derived a session in this process by walking its own ancestry to the outermost visible
 * parent, and so did every tool — which reached {@code explorer.exe} and made one session out of a whole
 * Windows login, or stopped at a shell that died with the command and made a new session per invocation.
 * Both, in the same keyring. The wallet now learns the calling process from the kernel and picks the
 * anchor itself, so the only honest way to report it is to ask the wallet what it decided.
 */
public final class Session {

    private Session() {
    }

    public static int run(WalletClient client, Args a) throws Exception {
        if (client == null) {
            System.err.println("No wallet is running, so there is nothing to ask.");
            System.err.println("A session is resolved by the wallet from the process that calls it; this");
            System.err.println("command cannot work it out on its own, and the old version that tried was");
            System.err.println("wrong in both directions.");
            return 3;
        }
        var raw = client.callRaw("session", new AccessRequest(null, null, "uskoag-walletcli", null, null,
                SessionId.current(), ProcessHandle.current().pid(), null, SessionId.sourcePid()));
        if (a.has("json")) {
            System.out.println(raw);
            return 0;
        }
        return print(uskoag.wallet.wire.Json.to(raw, SessionReport.class));
    }

    private static int print(SessionReport r) {
        if (r == null || r.anchor() == null) {
            System.out.println("session : (the wallet could not identify the calling process)");
            System.out.println("Every permission this run is granted will cover this one command only.");
            return 1;
        }
        System.out.println("session : " + r.anchor());
        System.out.println("pinned  : " + r.anchorDescription() + "   (" + r.how() + ")");
        System.out.println("caller  : pid " + r.callerPid()
                + (r.verified() ? " — confirmed by the kernel" : " — as claimed, NOT confirmed"));
        if (r.label() != null && !r.label().isBlank()) System.out.println("named   : " + r.label());
        System.out.println("chain   : " + r.chain());
        if (r.boundRules() >= 0) {
            System.out.println("rules   : " + r.boundRules() + " standing permission(s) bound to this run");
        }
        if (r.warning() != null && !r.warning().isBlank()) {
            System.out.println();
            System.out.println("WARNING: " + r.warning());
        }

        System.out.println();
        System.out.println("Every tool called from this process presents the same session, so one approval");
        System.out.println("covers the run and it ends when this process does.");
        System.out.println();
        System.out.println("To pin it somewhere else — a different agent or terminal in the chain above:");
        System.out.println("  PowerShell   $env:" + SessionId.SOURCE_ENV + " = \"<pid>\"");
        System.out.println("  bash         export " + SessionId.SOURCE_ENV + "=<pid>");
        System.out.println("The wallet checks that the pid really is an ancestor of the calling process and");
        System.out.println("discards it otherwise, so this can narrow or widen the run within its own tree");
        System.out.println("and nothing further.");
        System.out.println();
        System.out.println("To give the run a name that appears in the dialog and the audit:");
        System.out.println("  PowerShell   $env:" + SessionId.ENV + " = \"invoice reconciliation, March\"");
        System.out.println();
        System.out.println("To stop being asked at all, that is a different question:");
        System.out.println("  uskoag-walletcli policy quiet --tier write --this-run");
        System.out.println("  (--this-run pins it to this process, so it dies with the run rather than");
        System.out.println("   standing on a clock. Without it, the rule outlives the command.)");
        return 0;
    }
}

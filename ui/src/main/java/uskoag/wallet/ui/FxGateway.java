package uskoag.wallet.ui;

import javafx.application.Platform;
import uskoag.wallet.daemon.ApprovalGateway;
import uskoag.wallet.daemon.WalletCore;
import uskoag.wallet.wire.ApprovalAnswer;
import uskoag.wallet.wire.ApprovalAsk;
import uskoag.wallet.wire.AncestryHop;
import uskoag.wallet.wire.Match;
import leasegate.core.access.Approval;
import leasegate.core.access.PassphraseGate;
import leasegate.core.access.ProcessTree;
import leasegate.core.win.PeerIdentity;
import leasegate.ui.FxApproval;

import java.time.Instant;
import java.util.Optional;

/** The engine's route to a person: a real window, on the FX thread, with the worker blocked behind it. */
public final class FxGateway implements ApprovalGateway {

    private final WalletCore core;

    public FxGateway(WalletCore core) {
        this.core = core;
    }

    /**
     * A tool's request goes to the shared approval window, where the person anchors the answer at a process
     * in the caller's chain; a rule typed at the wallet's own command line only needs confirming.
     */
    @Override
    public ApprovalAnswer ask(ApprovalAsk ask) {
        return ask(ask, core.settings, PassphraseGate.external(s -> core.keyring.verify(s.toCharArray())));
    }

    static ApprovalAnswer ask(ApprovalAsk ask, uskoag.wallet.daemon.WalletSettings settings, PassphraseGate passphrase) {
        if (ask.chain().isEmpty()) return PolicyConfirm.ask(ask, settings, passphrase);

        var links = ask.chain().stream().map(h -> new ProcessTree.RawLink(h.pid(), h.imagePath(),
                h.startMillis() > 0 ? Optional.of(Instant.ofEpochMilli(h.startMillis())) : Optional.<Instant>empty()))
                .toList();
        var tree = ProcessTree.snapshot();
        var nodes = tree.decorate(links);
        var first = links.getFirst();
        var request = new Approval.Request<>(Approval.Kind.ATTESTED,
                new PeerIdentity(first.pid(), first.imagePath(), first.startInstant()), nodes, tree,
                WalletScope.of(ask, settings.destructiveOps), ask.appName(), null, ask.correlationCode());
        // Withdrawn before the client's own read timeout, so an answer never lands on a call already given up.
        var decision = new FxApproval<>(passphrase, new WalletRules(settings), Look.BRAND)
                .withTimeout(java.time.Duration.ofSeconds(PolicyConfirm.WAIT_SECONDS)).decide(request);
        if (decision.isEmpty() || decision.get().anchor() == null) return ApprovalAnswer.deny();

        var d = decision.get();
        var anchor = d.anchor();
        var id = AncestryHop.id(anchor.pid(), anchor.startInstant().map(Instant::toEpochMilli).orElse(0L));
        return ApprovalAnswer.anchored(id, anchor.imageName() + " pid " + anchor.pid(),
                Math.min(d.durationMinutes(), d.capMinutes()), d.scope().ops(), Match.ANY);
    }

    @Override
    public void unlockNeeded(String because) {
        // A debug run is unattended by definition, so there is nobody to answer this window — and if
        // somebody is at the machine, they are being interrupted by a question they did not cause.
        // Debug mode unlocks at startup only and deliberately never re-unlocks, so the honest response
        // to a lock during a debug run is to say what happened and let the caller fail: restarting the
        // wallet is what re-arms it. Found by locking the wallet during an automated test and putting a
        // passphrase prompt in front of someone who had gone to bed.
        if (uskoag.wallet.daemon.Debug.on()) {
            uskoag.wallet.daemon.Log.warn("locked during a debug run, and no window will be shown: "
                    + because + ". Restart uskoag-wallet.exe --debug to unlock it again.");
            return;
        }
        Platform.runLater(() -> UnlockWindow.show(core, null, UnlockAsk.forTool(because)));
    }

    @Override
    public void locked() {
        Platform.runLater(() -> {
            MainWindow.hide();
            Tray.note(uskoag.wallet.wire.Brand.NAME, "Locked itself after "
                    + core.settings.autoLockMinutes + " idle minute(s). Unlock from the tray, or just run"
                    + " a tool and it will ask.");
        });
    }

    @Override
    public void authUrl(String account, String url) {
        AuthUrlWindow.show(account, url);
    }

    @Override
    public void showWindow() {
        Platform.runLater(() -> {
            if (core.keyring.unlocked()) MainWindow.show(core);
            else UnlockWindow.show(core, () -> MainWindow.show(core), UnlockAsk.forTool(null));
        });
    }

    /**
     * Answering yes opens a browser, so this asks rather than announces. It is also the moment the old
     * store's worst failure becomes visible instead of silent: a tool needing wider scopes than were
     * ever granted used to reuse the narrow token and die on an opaque 403 much later.
     */
    @Override
    public boolean consentNeeded(String account, java.util.List<String> missingScopes) {
        return Ui.onFx(() -> Confirm.ask("Consent needed",
                account + " has never granted " + missingScopes.size() + " scope(s) this tool needs:\n\n  "
                        + String.join("\n  ", missingScopes)
                        + "\n\nConsent now? A browser window will open. Everything already granted is kept.",
                "Open browser", "Not now"));
    }
}

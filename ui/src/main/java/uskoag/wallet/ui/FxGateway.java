package uskoag.wallet.ui;

import javafx.application.Platform;
import uskoag.wallet.daemon.ApprovalGateway;
import uskoag.wallet.daemon.WalletCore;
import uskoag.wallet.wire.ApprovalAnswer;
import uskoag.wallet.wire.ApprovalAsk;

/** The engine's route to a person: a real window, on the FX thread, with the worker blocked behind it. */
public final class FxGateway implements ApprovalGateway {

    private final WalletCore core;

    public FxGateway(WalletCore core) {
        this.core = core;
    }

    @Override
    public ApprovalAnswer ask(ApprovalAsk ask) {
        return ApprovalWindow.ask(ask, core.settings, core.keyring::verify);
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

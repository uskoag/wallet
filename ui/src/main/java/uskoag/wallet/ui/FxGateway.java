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
        return ApprovalWindow.ask(ask, core.settings.destructiveOps, core.settings.destructiveMinutes);
    }

    @Override
    public void unlockNeeded(String because) {
        Platform.runLater(() -> UnlockWindow.show(core, null, because));
    }

    @Override
    public void authUrl(String account, String url) {
        AuthUrlWindow.show(account, url);
    }

    @Override
    public void showWindow() {
        Platform.runLater(() -> {
            if (core.keyring.unlocked()) MainWindow.show(core);
            else UnlockWindow.show(core, () -> MainWindow.show(core), null);
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

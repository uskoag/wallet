package uskoag.wallet.ui;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import uskoag.wallet.daemon.WalletCore;

import static luvjfx.Fx.borderPane;
import static luvjfx.Fx.label;
import static luvjfx.Fx.scene;
import static luvjfx.Fx.tabPane;

/** Accounts, standing permissions, audit. Everything else the wallet does happens without a window. */
public final class MainWindow {

    private static Stage stage;

    private MainWindow() {
    }

    /**
     * Builds a tab, or says why it could not, rather than rendering nothing.
     *
     * <p>An empty window is the same class of failure as the "received no bytes" fixed in the proxy and
     * the control server: it looks like nothing rather than like an error, so it gets diagnosed as a
     * rendering problem, or a data problem, or "it is probably overloaded". The actual cause is almost
     * always mundane and always the same one — <b>the jar was rebuilt underneath the running wallet</b>,
     * so the next class this pane needs cannot load. That is the one failure a person cannot guess at
     * and can fix in five seconds once told.
     *
     * <p>Catches {@link Throwable} deliberately: {@link LinkageError} is an {@code Error}, so catching
     * {@code Exception} here would leave exactly the case this exists for unhandled.
     */
    private static javafx.scene.Node tab(java.util.function.Supplier<javafx.scene.Node> build) {
        try {
            return build.get();
        } catch (Throwable t) {
            uskoag.wallet.daemon.Log.error("could not build a tab", t);
            var why = t instanceof LinkageError
                    ? "This wallet is running from a jar that has since been rebuilt.\n\n"
                      + "Close it and start uskoag-wallet again — nothing is lost, and the keyring is untouched."
                    : "This tab could not be built:\n\n" + t;
            var l = new javafx.scene.control.Label(why);
            l.setWrapText(true);
            l.setStyle("-fx-padding: 24; -fx-font-size: 13px;");
            return l;
        }
    }

    public static void show(WalletCore core) {
        // Refused over a locked keyring, here rather than only at the call sites. Every tab reads out of
        // the keyring, so a locked one produces five empty tabs and — worse — panes that stated an empty
        // result as a fact: "No accounts yet" over three intact accounts. Guarding each caller is how the
        // tray came to be the one that did not, so the guard belongs at the single point everything goes
        // through. The caller wanting a window on a locked wallet is asking in the wrong order; ask for
        // the passphrase, then show it.
        if (!core.keyring.unlocked()) {
            UnlockWindow.show(core, () -> show(core),
                    "Locked — the passphrase is needed before there is anything to show.");
            return;
        }
        if (stage != null) {
            Ui.toFront(stage);
            return;
        }
        stage = new Stage();
        stage.setTitle(uskoag.wallet.wire.Brand.NAME);
        AppIcon.applyTo(stage);

        var tabs = tabPane().attr(t -> {
            t.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            t.getTabs().addAll(
                    new Tab("Clients", tab(() -> OrgsPane.build(core))),
                    new Tab("Accounts", tab(() -> AccountsPane.build(core))),
                    new Tab("Permissions", tab(() -> PolicyPane.build(core))),
                    new Tab("Audit", tab(() -> AuditPane.build(core))),
                    new Tab("Settings", tab(() -> SettingsPane.build(core))));
            t.getSelectionModel().select(1);
        });

        var root = borderPane()
                .center(tabs)
                .bottom(label("  proxy on 127.0.0.1:" + core.proxyPortValue()
                        + "   ·   Esc hides this window; the wallet keeps running in the tray  ")
                        .style("-fx-font-size: 11px; -fx-text-fill: #666;"));

        var sc = scene(root.style(Ui.INK), 860, 520);
        Ui.escCloses(sc, stage, null);
        stage.setScene(sc);
        Ui.toFront(stage);
    }

    public static void hide() {
        if (stage != null) stage.hide();
    }
}

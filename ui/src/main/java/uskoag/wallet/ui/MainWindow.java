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

    public static void show(WalletCore core) {
        if (stage != null) {
            Ui.toFront(stage);
            return;
        }
        stage = new Stage();
        stage.setTitle("uskoag wallet");
        AppIcon.applyTo(stage);

        var tabs = tabPane().attr(t -> {
            t.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            t.getTabs().addAll(
                    new Tab("Clients", OrgsPane.build(core)),
                    new Tab("Accounts", AccountsPane.build(core)),
                    new Tab("Permissions", PolicyPane.build(core)),
                    new Tab("Audit", AuditPane.build(core)));
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

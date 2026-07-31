package uskoag.wallet.ui;

import javafx.application.Platform;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import uskoag.wallet.daemon.Recovery;
import uskoag.wallet.daemon.WalletCore;

import static luvjfx.Fx.button;
import static luvjfx.Fx.hbox;
import static luvjfx.Fx.label;
import static luvjfx.Fx.scene;
import static luvjfx.Fx.textField;
import static luvjfx.Fx.vbox;

/**
 * Starting over when the passphrase is gone.
 *
 * <p>There is no back door and there should not be — a keyring that opens without its passphrase is not
 * a keyring. So this says plainly what will be lost, plainly what will survive, and then makes you type
 * a word rather than click a button, because this is not a thing to do by accident.
 */
public final class ResetWindow {

    private static final String CONFIRM_WORD = "RESET";

    private ResetWindow() {
    }

    public static void show(WalletCore core, Runnable onUnlocked) {
        var stage = new Stage();
        stage.setAlwaysOnTop(true);
        stage.setTitle("uskoag wallet - start over");
        AppIcon.applyTo(stage);

        var kept = Recovery.pending();
        var typed = textField().promptText(CONFIRM_WORD);
        var status = label("");
        var go = button("Start over");
        var back = button("Go back").cancelButton(true);

        var root = vbox().spacing(10).padding(18).nodes(
                label("Start over with a new keyring")
                        .style("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #b71c1c;"),
                label("There is no way to open the old keyring without its passphrase. What this does instead"
                        + " is move it aside and begin a new one.").wrapText(true),
                label("Lost: every stored refresh token, so each account consents once more."
                        + "  Lost: the standing permissions, rebuilt as you work."
                        + "  Lost: the audit's detail columns, whose key lived in that keyring.").wrapText(true)
                        .style("-fx-text-fill: #b71c1c;"),
                label("Kept: " + kept + " credentials.json file(s) from the backup, so nothing has to be fetched"
                        + " from the Cloud console again.  Kept: the old keyring file, moved aside - if the"
                        + " passphrase comes back to you next week, it is still there.").wrapText(true)
                        .style("-fx-text-fill: #1b5e20;"),
                label("Type " + CONFIRM_WORD + " to confirm:"),
                typed,
                status.wrapText(true).style("-fx-text-fill: #b71c1c;"),
                hbox().spacing(8).nodes(go, back),
                label("Esc goes back").style("-fx-font-size: 11px; -fx-text-fill: #777;"));

        Runnable reset = () -> {
            if (!CONFIRM_WORD.equals(((TextField) typed.node).getText().trim())) {
                status.text("Type " + CONFIRM_WORD + " exactly to confirm.");
                return;
            }
            try {
                Recovery.reset(core);
                stage.close();
                UnlockWindow.show(core, onUnlocked, "Old keyring moved aside. Set a new passphrase.");
            } catch (Exception e) {
                status.text(String.valueOf(e.getMessage()));
            }
        };

        go.attr(b -> b.setOnAction(e -> reset.run()));
        typed.attr(f -> f.setOnAction(e -> reset.run()));
        back.attr(b -> b.setOnAction(e -> {
            stage.close();
            UnlockWindow.show(core, onUnlocked, null);
        }));

        var sc = scene(root.style(Ui.INK), 560, 400);
        Ui.escCloses(sc, stage, () -> UnlockWindow.show(core, onUnlocked, null));
        stage.setScene(sc);
        stage.setOnShown(e -> Platform.runLater(() -> {
            stage.toFront();
            stage.requestFocus();
            typed.node.requestFocus();
        }));
        Ui.toFront(stage);
    }
}

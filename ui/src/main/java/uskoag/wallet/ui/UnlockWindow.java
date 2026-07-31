package uskoag.wallet.ui;

import javafx.application.Platform;
import javafx.scene.control.PasswordField;
import javafx.stage.Stage;
import uskoag.wallet.daemon.CredentialsBackup;
import uskoag.wallet.daemon.Keyring;
import uskoag.wallet.daemon.WalletCore;

import java.util.Arrays;

import static luvjfx.Fx.button;
import static luvjfx.Fx.hbox;
import static luvjfx.Fx.label;
import static luvjfx.Fx.passwordField;
import static luvjfx.Fx.scene;
import static luvjfx.Fx.vbox;

/**
 * Unlocking, creating and — when the passphrase is gone — starting over.
 *
 * <p>Creating asks twice. A passphrase mistyped once at creation is not discovered until the next boot,
 * by which time there is nothing to compare it against and every stored refresh token is unreachable.
 * Unlocking afterwards asks once, because a wrong answer there costs a retry and nothing else.
 */
public final class UnlockWindow {

    private static Stage open;

    private UnlockWindow() {
    }

    public static void show(WalletCore core, Runnable onUnlocked, String because) {
        if (open != null && open.isShowing()) {
            Ui.toFront(open);
            return;
        }
        var creating = !core.keyring.exists();
        var stage = new Stage();
        open = stage;
        stage.setAlwaysOnTop(true);
        stage.setTitle(creating ? "uskoag wallet - create keyring" : "uskoag wallet - unlock");
        AppIcon.applyTo(stage);

        var first = passwordField();
        var confirm = passwordField();
        var status = label(because == null ? "" : because);
        var go = button(creating ? "Create keyring" : "Unlock").defaultButton(true);
        var forgot = button("Forgot passphrase...");
        var quit = button("Quit").cancelButton(true);

        var body = vbox().spacing(10).padding(18);
        var heading = label(creating ? "Set a passphrase for this machine" : "uskoag wallet")
                .style("-fx-font-size: 18px; -fx-font-weight: bold;");
        var blurb = label(creating
                ? "This is the only thing standing between anything on this machine and every Google account"
                  + " it can reach. It is never stored anywhere. Type it twice."
                : "Everything Google that this machine can reach is behind this passphrase.").wrapText(true);

        Runnable attempt = () -> {
            var typed = ((PasswordField) first.node).getText().toCharArray();
            var repeat = creating ? ((PasswordField) confirm.node).getText().toCharArray() : typed;
            try {
                if (creating && !Arrays.equals(typed, repeat)) {
                    fail(status, first, confirm, "The two entries did not match. Both boxes cleared - type it again.");
                    return;
                }
                if (creating && typed.length < Keyring.MIN_PASSPHRASE) {
                    fail(status, first, confirm, "Use at least " + Keyring.MIN_PASSPHRASE
                            + " characters. Both boxes cleared.");
                    return;
                }
                core.unlock(typed);
                if (creating) {
                    var restored = CredentialsBackup.restoreInto(core);
                    if (restored > 0) {
                        Tray.note("uskoag wallet", restored + " credentials.json restored from backup."
                                + " Each account still needs 'Log in' once.");
                    }
                }
                stage.close();
                open = null;
                if (onUnlocked != null) onUnlocked.run();
            } catch (Exception e) {
                fail(status, first, confirm, "That did not unlock the keyring. Cleared - type it again."
                        + " Nothing was sent anywhere; the check is local.");
            } finally {
                Arrays.fill(typed, '\0');
                Arrays.fill(repeat, '\0');
            }
        };

        go.attr(b -> b.setOnAction(e -> attempt.run()));
        quit.attr(b -> b.setOnAction(e -> System.exit(0)));
        forgot.attr(b -> b.setOnAction(e -> {
            stage.close();
            open = null;
            ResetWindow.show(core, onUnlocked);
        }));
        first.attr(f -> f.setOnAction(e -> {
            if (creating) confirm.node.requestFocus();
            else attempt.run();
        }));
        confirm.attr(f -> f.setOnAction(e -> attempt.run()));

        body.nodes(heading, blurb, label(creating ? "Passphrase" : ""), first);
        if (creating) body.nodes(label("Passphrase again"), confirm);
        body.nodes(status.wrapText(true).style("-fx-text-fill: #b71c1c;"),
                hbox().spacing(8).nodes(go, creating ? quit : forgot, creating ? label("") : quit),
                label(creating
                        ? "Enter moves to the second box, then creates   |   Esc quits"
                        : "Enter unlocks   |   Esc quits   |   nothing is written to disk in clear")
                        .style("-fx-font-size: 11px; -fx-text-fill: #777;"));

        var sc = scene(body.style(Ui.INK), 520, creating ? 340 : 290);
        Ui.escCloses(sc, stage, () -> System.exit(0));
        stage.setScene(sc);
        // Focus has to be asked for after the stage is actually on screen, and again on the next pulse:
        // a window raised by a background process does not reliably get the foreground on Windows, and
        // requesting focus before that lands silently does nothing.
        stage.setOnShown(e -> Platform.runLater(() -> {
            stage.toFront();
            stage.requestFocus();
            first.node.requestFocus();
        }));
        Ui.toFront(stage);
    }

    private static void fail(luvjfx.FxLabel status, luvjfx.FxPasswordField first, luvjfx.FxPasswordField confirm,
                             String message) {
        status.text(message);
        ((PasswordField) first.node).clear();
        ((PasswordField) confirm.node).clear();
        first.node.requestFocus();
    }
}

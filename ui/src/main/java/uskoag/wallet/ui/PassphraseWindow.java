package uskoag.wallet.ui;

import javafx.application.Platform;
import javafx.scene.control.PasswordField;
import javafx.stage.Stage;
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
 * Changing the passphrase from the window rather than only from the command line.
 *
 * <p>There was a {@code passwd} verb and nothing on screen, which is the wrong way round for this
 * particular operation. Someone who wants to change a passphrase usually wants to because they think
 * the old one is compromised, and telling them at that moment to go and find a CLI is how it ends up
 * not being done. The CLI route stays; this is the one people will actually use.
 *
 * <p>Asked three times, the same shape as creating a keyring. The new one twice because a mistyped new
 * passphrase is not discovered until the next start, by which point there is nothing to compare it
 * against and every stored refresh token is unreachable. The current one because a wallet is left
 * unlocked on a desk, and without it anyone passing could rekey it and lock its owner out.
 *
 * <p>What does not move is worth saying out loud, because the fear is reasonable: only the one keyring
 * file is rewritten. The audit's column key lives inside the keyring rather than being derived from the
 * passphrase, so history stays readable across as many changes as you like, and nothing at Google is
 * touched at all.
 */
public final class PassphraseWindow {

    private PassphraseWindow() {
    }

    public static void show(WalletCore core) {
        if (!core.keyring.unlocked()) {
            Confirm.ask("Change passphrase", "Unlock the wallet first.\n\n"
                    + "Changing the passphrase re-encrypts the keyring, which cannot be done without"
                    + " being able to read it.", "OK", "Cancel");
            return;
        }

        var stage = new Stage();
        stage.setAlwaysOnTop(true);
        stage.setTitle(uskoag.wallet.wire.Brand.titled("change passphrase"));
        AppIcon.applyTo(stage);

        var current = passwordField();
        var fresh = passwordField();
        var again = passwordField();
        var status = label("");

        var go = button("Change it").defaultButton(true);
        var cancel = button("Cancel").cancelButton(true);

        Runnable attempt = () -> {
            var now = ((PasswordField) current.node).getText().toCharArray();
            var one = ((PasswordField) fresh.node).getText().toCharArray();
            var two = ((PasswordField) again.node).getText().toCharArray();
            try {
                if (!Arrays.equals(one, two)) {
                    fail(status, "The two new entries did not match. The new boxes are cleared - type it again.",
                            fresh, again);
                    return;
                }
                if (one.length < Keyring.MIN_PASSPHRASE) {
                    fail(status, "Use at least " + Keyring.MIN_PASSPHRASE + " characters. The new boxes are"
                            + " cleared.", fresh, again);
                    return;
                }
                if (Arrays.equals(now, one)) {
                    fail(status, "That is the passphrase it already has. Nothing changed.", fresh, again);
                    return;
                }
                core.keyring.changePassphrase(now, one);
                stage.close();
                Tray.note(uskoag.wallet.wire.Brand.NAME, "Passphrase changed. Every stored token and the"
                        + " whole audit came across unchanged.");
            } catch (Exception e) {
                // The message from the keyring already distinguishes a wrong current passphrase from a
                // new one that is too short, and saying which is not a leak: whoever is typing is
                // already looking at an unlocked wallet.
                fail(status, String.valueOf(e.getMessage()), current, fresh, again);
            } finally {
                Arrays.fill(now, '\0');
                Arrays.fill(one, '\0');
                Arrays.fill(two, '\0');
            }
        };

        go.attr(b -> b.setOnAction(e -> attempt.run()));
        cancel.attr(b -> b.setOnAction(e -> stage.close()));
        current.attr(f -> f.setOnAction(e -> fresh.node.requestFocus()));
        fresh.attr(f -> f.setOnAction(e -> again.node.requestFocus()));
        again.attr(f -> f.setOnAction(e -> attempt.run()));

        var body = vbox().spacing(10).padding(18).nodes(
                label("Change the passphrase").style("-fx-font-size: 18px; -fx-font-weight: bold;"),
                label("Only the keyring file is rewritten. Every stored token survives, the audit stays"
                        + " readable, and nothing at Google is touched — so this costs you nothing but"
                        + " the typing.").wrapText(true),
                label("Current passphrase"), current,
                label("New passphrase"), fresh,
                label("New passphrase again"), again,
                status.wrapText(true).style("-fx-text-fill: #b71c1c;"),
                hbox().spacing(8).nodes(go, cancel),
                label("Enter moves to the next box, then changes it   |   Esc cancels   |   nothing is"
                        + " written to disk in clear")
                        .style("-fx-font-size: 11px; -fx-text-fill: #777;"));

        var sc = scene(body.style(Ui.INK), 520, 430);
        Ui.escCloses(sc, stage, null);
        stage.setScene(sc);
        stage.setOnShown(e -> Platform.runLater(() -> Ui.grabFocus(stage, current.node)));
        Ui.grabFocus(stage, current.node);
    }

    private static void fail(luvjfx.FxLabel status, String message, luvjfx.FxPasswordField... clear) {
        status.text(message);
        for (var f : clear) ((PasswordField) f.node).clear();
        if (clear.length > 0) clear[0].node.requestFocus();
    }
}

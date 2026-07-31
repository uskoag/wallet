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

    /** Seconds of no typing before an unattended passphrase box gives up and hides itself. */
    private static final int IDLE_SECONDS = 120;

    /**
     * The one open prompt, so several tools arriving at a locked wallet at once get a single box rather
     * than one each stacked on top of each other. Only ever touched on the FX thread.
     */
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
        var closing = label("");
        body.nodes(status.wrapText(true).style("-fx-text-fill: #b71c1c;"),
                hbox().spacing(8).nodes(go, creating ? quit : forgot, creating ? label("") : quit),
                label(creating
                        ? "Enter moves to the second box, then creates   |   Esc hides this   |   Quit stops the wallet"
                        : "Enter unlocks   |   Esc hides this   |   Quit stops the wallet"
                          + "   |   nothing is written to disk in clear")
                        .style("-fx-font-size: 11px; -fx-text-fill: #777;"),
                closing.style("-fx-font-size: 10px; -fx-text-fill: #999;"));

        var sc = scene(body.style(Ui.INK), 520, creating ? 380 : 330);
        // Esc hides, it does not quit. The wallet is a service now: every tool on this machine reaches
        // Google through it, so dismissing a dialog must not take the service down with it. Quit still
        // does exactly what it says.
        Ui.escCloses(sc, stage, () -> open = null);
        stage.setScene(sc);
        stage.setOnShown(e -> Platform.runLater(() -> Ui.grabFocus(stage, first.node)));
        idleClose(stage, closing, first, confirm);
        Ui.grabFocus(stage, first.node);
    }

    /**
     * Closes an untouched passphrase box after a while, counting down quietly first.
     *
     * <p>Idle rather than absolute: the timer resets on every keystroke, because a box that vanishes
     * mid-passphrase is worse than one that lingers. What is being avoided is the other case — an unlock
     * prompt left open and unattended on a machine that every tool here reaches Google through.
     *
     * <p>Closing only hides the window. Nothing is waiting on it: a client that found the wallet locked
     * was told to retry, so there is no request to fail.
     */
    private static void idleClose(Stage stage, luvjfx.FxLabel closing,
                                 luvjfx.FxPasswordField first, luvjfx.FxPasswordField confirm) {
        var left = new java.util.concurrent.atomic.AtomicInteger(IDLE_SECONDS);
        var clock = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.seconds(1), e -> {
            var n = left.decrementAndGet();
            if (n <= 0) {
                stage.close();
                open = null;
                return;
            }
            closing.text("closes in " + n / 60 + ":" + String.format("%02d", n % 60)
                    + " if untouched   ·   reopen from the tray");
        }));
        clock.setCycleCount(javafx.animation.Animation.INDEFINITE);
        clock.play();

        Runnable reset = () -> left.set(IDLE_SECONDS);
        first.node.setOnKeyTyped(e -> reset.run());
        confirm.node.setOnKeyTyped(e -> reset.run());
        stage.setOnHidden(e -> clock.stop());
    }

    private static void fail(luvjfx.FxLabel status, luvjfx.FxPasswordField first, luvjfx.FxPasswordField confirm,
                             String message) {
        status.text(message);
        ((PasswordField) first.node).clear();
        ((PasswordField) confirm.node).clear();
        first.node.requestFocus();
    }
}

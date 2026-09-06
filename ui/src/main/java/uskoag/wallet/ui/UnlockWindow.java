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
 * Unlocking, creating and — when the passphrase is gone — starting over.
 *
 * <p>Creating asks twice. A passphrase mistyped once at creation is not discovered until the next boot,
 * by which time there is nothing to compare it against and every stored refresh token is unreachable.
 * Unlocking afterwards asks once, because a wrong answer there costs a retry and nothing else.
 *
 * <p>How hard the window pushes is carried by {@link UnlockAsk} rather than fixed here, because the wallet
 * now asks for two different reasons: a tool blocked mid-call, and the daily credential check, which is
 * happy to wait.
 */
public final class UnlockWindow {

    /**
     * The reason line, and the failure line, in that order — they are the same label and they were the
     * same colour, which was wrong in one direction.
     *
     * <p>Everything under the passphrase box used to be red. But a reason is not an error: "the daily
     * credential check needs the keyring open" printed in the colour reserved for failure says something
     * has gone wrong at the exact moment nothing has, and on the one prompt in this application with
     * nothing blocked behind it. A wrong passphrase still goes red, because that one is a failure.
     */
    private static final String
            REASON = "-fx-text-fill: #444;",
            FAILED = "-fx-text-fill: #b71c1c;";

    /**
     * The one open prompt, so several tools arriving at a locked wallet at once get a single box rather
     * than one each stacked on top of each other. Only ever touched on the FX thread.
     */
    private static Stage open;

    /** What the open box is for, so a blocked tool can displace a maintenance prompt. */
    private static UnlockAsk openAsk;

    private UnlockWindow() {
    }

    public static void show(WalletCore core, Runnable onUnlocked, String because) {
        show(core, onUnlocked, UnlockAsk.forTool(because));
    }

    public static void show(WalletCore core, Runnable onUnlocked, UnlockAsk ask) {
        if (open != null && open.isShowing()) {
            // A tool that has stopped mid-call outranks the daily check, and it has to, or the reason on
            // screen is the wrong one: raising the maintenance box for a blocked tool would tell somebody
            // that nothing is waiting at the exact moment something is.
            if (ask.insistent() && openAsk != null && !openAsk.insistent()) {
                open.close();
                open = null;
            } else {
                if (ask.insistent()) Ui.toFront(open);
                return;
            }
        }
        var creating = !core.keyring.exists();
        var stage = new Stage();
        open = stage;
        openAsk = ask;
        stage.setAlwaysOnTop(ask.insistent());
        stage.setTitle(uskoag.wallet.wire.Brand.titled(creating ? "create keyring" : "unlock"));
        AppIcon.applyTo(stage);

        var first = passwordField();
        var confirm = passwordField();

        // Only when a keyring is being created. Unlocking an existing one must accept anything that was
        // ever a valid passphrase, including one set before this rule existed — Keyring.unlock tries the
        // typed form and the normalised form, and filtering here would remove the typed form from reach.
        if (creating) {
            PassphraseWindow.lettersOnly(first);
            PassphraseWindow.lettersOnly(confirm);
        }

        var status = label(ask.because() == null ? "" : ask.because());
        var go = button(creating ? "Create keyring" : "Unlock").defaultButton(true);
        var forgot = button("Forgot passphrase...");
        var quit = button("Quit").cancelButton(true);

        var body = vbox().spacing(10).padding(18);
        var heading = label(creating ? "Set a passphrase for this machine" : uskoag.wallet.wire.Brand.NAME)
                .style("-fx-font-size: 18px; -fx-font-weight: bold;");
        var blurb = label(creating
                ? "This is the only thing standing between anything on this machine and every Google account"
                  + " it can reach. It is never stored anywhere. Type it twice."
                : ask.blurb() != null ? ask.blurb()
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
                stage.close();
                open = null;
                openAsk = null;
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
            openAsk = null;
            ResetWindow.show(core, onUnlocked);
        }));
        first.attr(f -> f.setOnAction(e -> {
            if (creating) confirm.node.requestFocus();
            else attempt.run();
        }));
        confirm.attr(f -> f.setOnAction(e -> attempt.run()));

        body.nodes(heading, blurb, label(creating
                ? "Passphrase (letters only - digits and symbols are dropped as you type, case doesn't matter)"
                : ""), first);
        if (creating) body.nodes(label("Passphrase again"), confirm);
        var closing = label("");
        body.nodes(status.wrapText(true).style(REASON),
                hbox().spacing(8).nodes(go, creating ? quit : forgot, creating ? label("") : quit),
                label(creating
                        ? "Enter moves to the second box, then creates   |   Esc hides this   |   Quit stops the wallet"
                        : "Enter unlocks   |   Esc hides this   |   Quit stops the wallet"
                          + "   |   nothing is written to disk in clear")
                        .style("-fx-font-size: 11px; -fx-text-fill: #777;"),
                closing.style("-fx-font-size: 10px; -fx-text-fill: #999;"));

        var sc = scene(body.style(Ui.INK), 520, creating ? 380 : ask.blurb() == null ? 330 : 400);
        // Esc hides, it does not quit. The wallet is a service now: every tool on this machine reaches
        // Google through it, so dismissing a dialog must not take the service down with it. Quit still
        // does exactly what it says.
        Ui.escCloses(sc, stage, () -> {
            open = null;
            openAsk = null;
        });
        stage.setScene(sc);
        idleClose(stage, closing, first, confirm, ask.idleSeconds());
        if (ask.insistent()) {
            stage.setOnShown(e -> Platform.runLater(() -> Ui.grabFocus(stage, first.node)));
            Ui.grabFocus(stage, first.node);
            // toFront()/requestFocus() only raise the window WITHIN whatever macOS Space this process's
            // window already lives on - they do not switch the user to that Space, so a blocked tool's
            // unlock prompt can sit unseen on a Space nobody is looking at. Bouncing the dock icon is
            // visible from any Space and does not go away on its own, unlike toFront().
            try {
                var taskbar = java.awt.Taskbar.getTaskbar();
                if (taskbar.isSupported(java.awt.Taskbar.Feature.USER_ATTENTION)) {
                    taskbar.requestUserAttention(true, true);
                }
            } catch (Throwable t) {
                uskoag.wallet.daemon.Log.warn("could not request user attention for insistent unlock: " + t);
            }
        } else {
            // Visible without seizing the keyboard. The caret is put in the box inside the scene, so the
            // window is still typed into the moment it is picked up — but nothing is taken away from
            // whatever is being typed into right now, which is the whole point of a quiet ask.
            stage.show();
            stage.toFront();
            first.node.requestFocus();
        }
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
                                 luvjfx.FxPasswordField first, luvjfx.FxPasswordField confirm,
                                 int idleSeconds) {
        var left = new java.util.concurrent.atomic.AtomicInteger(idleSeconds);
        var clock = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.seconds(1), e -> {
            var n = left.decrementAndGet();
            if (n <= 0) {
                stage.close();
                open = null;
                openAsk = null;
                return;
            }
            closing.text("closes in " + n / 60 + ":" + String.format("%02d", n % 60)
                    + " if untouched   ·   reopen from the tray");
        }));
        clock.setCycleCount(javafx.animation.Animation.INDEFINITE);
        clock.play();

        Runnable reset = () -> left.set(idleSeconds);
        first.node.setOnKeyTyped(e -> reset.run());
        confirm.node.setOnKeyTyped(e -> reset.run());
        stage.setOnHidden(e -> clock.stop());
    }

    private static void fail(luvjfx.FxLabel status, luvjfx.FxPasswordField first, luvjfx.FxPasswordField confirm,
                             String message) {
        status.text(message);
        status.style(FAILED);
        ((PasswordField) first.node).clear();
        ((PasswordField) confirm.node).clear();
        first.node.requestFocus();
    }
}

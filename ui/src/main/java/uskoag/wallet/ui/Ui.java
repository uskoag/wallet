package uskoag.wallet.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The two things every window here needs: a way to run on the FX thread from a proxy worker and block
 * for the answer, and Esc wired to close.
 *
 * <p>Keyboard throughout, single keys, no chords — Tab moves, Enter accepts, Esc dismisses. Every dialog
 * is answerable without touching the mouse, and the status line at the bottom says how.
 */
public final class Ui {

    public static final String INK = "-fx-font-family: 'Segoe UI'; -fx-font-size: 13px;";

    private Ui() {
    }

    /** Blocks the calling thread until the FX thread produces a value. Never call this from FX. */
    public static <T> T onFx(Supplier<T> work) {
        if (Platform.isFxApplicationThread()) return work.get();
        var out = new AtomicReference<T>();
        var done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                out.set(work.get());
            } finally {
                done.countDown();
            }
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out.get();
    }

    public static void escCloses(Scene scene, Stage stage, Runnable onEsc) {
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                if (onEsc != null) onEsc.run();
                stage.close();
            }
        });
    }

    public static void toFront(Stage stage) {
        stage.show();
        stage.setIconified(false);
        stage.toFront();
        stage.requestFocus();
    }

    /**
     * Puts the window in front and the caret in the box, retrying for a moment.
     *
     * <p>Windows does not reliably hand the foreground to a window raised by a background process, and a
     * focus request that arrives before the stage is actually on screen does nothing at all — silently.
     * The result was a passphrase box you had to find and click before you could type into it, which is
     * mouse work added to the one dialog that appears most often. Pulsing the request over the first
     * half-second covers the cases where the first attempt lands too early or is refused outright.
     *
     * <p>Toggling always-on-top off and on again is part of it: on Windows that is what makes the shell
     * re-evaluate which window should be active.
     */
    public static void grabFocus(Stage stage, javafx.scene.Node target) {
        toFront(stage);
        var pulses = new java.util.concurrent.atomic.AtomicInteger(5);
        var timer = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(120), e -> {
            if (!stage.isShowing()) return;
            stage.setAlwaysOnTop(false);
            stage.setAlwaysOnTop(true);
            stage.toFront();
            stage.requestFocus();
            if (target != null && !target.isFocused()) target.requestFocus();
        }));
        timer.setCycleCount(pulses.get());
        timer.play();
        stage.setOnHidden(e -> timer.stop());
    }
}

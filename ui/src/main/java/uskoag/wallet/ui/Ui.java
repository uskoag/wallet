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

    /** Title bar and borders: a stage's height includes them, a scene's does not. */
    private static final int CHROME = 40;

    /**
     * Sizes a window to the height its content actually needs, and never past the usable screen.
     *
     * <p>Here rather than in each window because every one of them carried a hand-picked height, and a
     * constant cannot be right for content that varies: an approval dialog shows different rows per tier,
     * a resolved document name wraps to one line or three, a scope list is as long as the account's
     * consents. Every such number was wrong in both directions at once — dead space at the bottom of the
     * short cases, and the tall cases running past the bottom edge of the screen, where a control is not
     * cramped but invisible.
     *
     * <p>Call it after {@code setScene}: {@code prefHeight} is meaningless until CSS has been applied,
     * since font sizes here are set in style strings and every wrapped label's height depends on them.
     *
     * @param content the scene root, or the node inside a scroll pane when there is one
     */
    static void fitToContent(Stage stage, javafx.scene.Parent content, double width) {
        content.applyCss();
        content.layout();
        var usable = javafx.stage.Screen.getPrimary().getVisualBounds();
        stage.setWidth(width);
        stage.setHeight(Math.min(content.prefHeight(width) + CHROME, usable.getHeight() * 0.94));
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

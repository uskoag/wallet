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
}

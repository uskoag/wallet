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

    /**
     * Title bar and resize borders, used only before the stage is showing and can report its own. Once it
     * is on screen the real numbers are read off it, because these vary with the Windows theme and the
     * display's scaling and a guess that is wrong by ten pixels is a guess that puts a scroll bar on a
     * window that had no need of one.
     */
    private static final int CHROME_H = 39, CHROME_W = 16;

    /** Never taller than this share of what the taskbar leaves. */
    private static final double SCREEN_SHARE = 0.94;

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
     * <p>Three things make the fit exact rather than approximately right, and the first version had none
     * of them, which is why it produced a window that was both too short AND scrolling:
     *
     * <ul>
     * <li><b>The measuring width has to be the width the content is laid out at.</b> Setting the STAGE to
     *     660 gives the SCENE about 646, so every wrapped label was measured one notch wider than it would
     *     ever be drawn, and each one that then wrapped to an extra line was a line the height did not
     *     include. Off by a few lines is off by enough to need a scroll bar.
     * <li><b>It runs again once the stage is showing</b>, when the decoration insets are facts instead of
     *     constants, and again on every later change of the content's height — an error line under the
     *     passphrase box wraps to two, an armed-key note appears. A window sized once is correct once.
     * <li><b>The content sits in a scroll pane so that it can never be squeezed</b>, and that is what makes
     *     the second point work at all. A bare {@code VBox} scene root is resized to the SCENE's height,
     *     so a window that opened too short laid its labels out at their minimum — one ellipsised line
     *     each — and its own height then stopped changing, which meant nothing ever asked for a re-fit.
     *     Measured live: the first approval window opened at 400px for content needing 582px and simply
     *     stayed there, with six paragraphs truncated to "…". Inside a scroll pane the content always has
     *     its preferred height, so {@code heightProperty} carries the truth and the loop is self-healing.
     * </ul>
     *
     * <p>The bar itself is off unless the content genuinely cannot fit the screen, so the ordinary case
     * pays neither a gutter nor a scroll.
     *
     * <p>Call it after {@code setScene}: {@code prefHeight} is meaningless until CSS has been applied,
     * since font sizes here are set in style strings and every wrapped label's height depends on them.
     *
     * @param content the current scene root; it is moved inside a scroll pane
     * @param surface the colour the content is painted on, or null for the platform default. NOT
     *                cosmetic — see below.
     */
    static void fitToContent(Stage stage, javafx.scene.layout.Region content, double width,
                             String surface) {
        var sp = new javafx.scene.control.ScrollPane();
        sp.setFitToWidth(true);
        sp.setFocusTraversable(false);
        sp.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        /*
         * `-fx-background`, and NOT `-fx-background: transparent`, which is what was here and is the
         * single most-copied line on the internet for making a scroll pane invisible.
         *
         * It works, and it also turns text white. Modena derives the default text colour with
         * `ladder(-fx-background, …)`: light text on a dark surface, dark text on a light one, decided
         * per subtree. `transparent` is black with zero alpha, the ladder reads a black surface, and
         * every descendant that did not name its own -fx-text-fill switches to white — inherited by
         * everything inside, including a pale cream approval dialog, where the headline, the document
         * name, the breadth checkbox and the "Approve for" line are exactly the labels that trusted the
         * default. White on #fff4e5 is 1.05:1. That is not low contrast; it is invisible.
         *
         * Naming the real surface makes the ladder come out right AND paints the viewport the right
         * colour, which is what `transparent` was reached for in the first place.
         */
        sp.setStyle((surface == null ? "" : "-fx-background: " + surface + ";")
                + " -fx-background-color: transparent; -fx-padding: 0;"
                + " -fx-background-insets: 0; -fx-border-width: 0;");
        // Root first, then content: a node cannot be a scene's root and somebody's child at once.
        stage.getScene().setRoot(sp);
        sp.setContent(content);

        // Measured ONCE, the first time the stage is on screen, and then trusted.
        //
        // Deriving it every pass looks more careful and is in fact broken: a Scene does not resize the
        // instant setHeight is called on its Stage, so the next pass subtracts the OLD scene height from
        // the NEW stage height and reads the difference as decoration. The error is the size of the last
        // change and it compounds — traced live, one window went 437 → 620 → 803 on unchanged content,
        // each step adding the previous step's growth back as phantom title bar.
        var chrome = new java.util.concurrent.atomic.AtomicReference<javafx.geometry.Point2D>();
        Runnable fit = () -> {
            var scene = stage.getScene();
            if (chrome.get() == null && stage.isShowing() && scene.getWidth() > 0)
                chrome.set(new javafx.geometry.Point2D(stage.getWidth() - scene.getWidth(),
                        stage.getHeight() - scene.getHeight()));
            var c = chrome.get();
            resize(stage, sp, content, width,
                    c == null ? CHROME_W : c.getX(), c == null ? CHROME_H : c.getY());
        };
        fit.run();
        stage.setOnShown(e -> Platform.runLater(fit));
        content.heightProperty().addListener((o, was, now) -> Platform.runLater(fit));
    }

    private static void resize(Stage stage, javafx.scene.control.ScrollPane sp,
                               javafx.scene.layout.Region content, double width, double dw, double dh) {
        // From the ROOT, not from the content: CSS cascades downward, and applying it to a node whose
        // parent has not had it applied leaves inherited font sizes unresolved — which pre-show measured
        // this window's content at 198px instead of 582px.
        var root = stage.getScene().getRoot();
        root.applyCss();
        root.layout();

        var max = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight() * SCREEN_SHARE;
        var need = Math.ceil(Math.max(content.getHeight(), content.prefHeight(width))) + dh;

        // Reachable content beats clipped content, so a window that cannot fit the screen scrolls. A
        // window that fits does not, and does not carry the gutter for one either.
        sp.setVbarPolicy(need > max
                ? javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED
                : javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);

        var h = Math.min(need, max);
        if (Math.abs(stage.getWidth() - (width + dw)) > 0.5) stage.setWidth(width + dw);
        if (Math.abs(stage.getHeight() - h) > 0.5) stage.setHeight(h);

        // Before it is on screen, setHeight is not enough: a Stage sizes itself from its Scene's preferred
        // size at show(), so the window appeared at the Scene's provisional height and then jumped. The
        // viewport preference is what the Scene's preference is made of, so state it and ask for it.
        if (!stage.isShowing()) {
            sp.setPrefViewportWidth(width);
            sp.setPrefViewportHeight(h - dh);
            stage.sizeToScene();
        }
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

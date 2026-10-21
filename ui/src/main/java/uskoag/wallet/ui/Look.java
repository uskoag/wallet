package uskoag.wallet.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.WindowEvent;
import leasegate.frame.Brand;
import leasegate.frame.GateFrame;
import leasegate.frame.GateIcons;

/**
 * The wallet's colour and icons, shared by its tray and every window it frames.
 *
 * <p>The colour is the blue of the front document in the icon, so the band, the glow and the icon read
 * as one thing.
 */
public final class Look {

    public static final String COLOR = "#1a73e8";

    public static final Brand BRAND = new Brand(uskoag.wallet.wire.Brand.NAME, COLOR,
            new GateIcons(Look.class, "/icons/uskoag-wallet"));

    /**
     * A window in the wallet's frame: its colour as the title band, the glow while the keyboard types
     * into it, its icons for the taskbar theme. The frame owns the stage and sets its title.
     */
    public static GateFrame frame(String subtitle, boolean resizable) {
        return resizable ? GateFrame.resizable(BRAND, subtitle) : new GateFrame(BRAND, subtitle);
    }

    /** Puts `body`, sized as the window's content used to be, into the frame and returns the scene. */
    public static Scene put(GateFrame frame, Node body, double width, double height) {
        if (body instanceof Region r) r.setPrefSize(width, height);
        return frame.scene(body);
    }

    /**
     * For a window whose height depends on its content (the approval, grant and open-access windows): the
     * content at its width, as tall as it needs, inside the frame. A scroll bar appears only if it would
     * otherwise run off the screen, and the window is held to the screen's height.
     */
    public static Scene fit(GateFrame frame, Region content, double width, String surface) {
        content.setPrefWidth(width);
        content.setMinWidth(width);
        var sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setFocusTraversable(false);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setStyle((surface == null ? "" : "-fx-background: " + surface + ";")
                + " -fx-background-color: transparent; -fx-padding: 0; -fx-background-insets: 0; -fx-border-width: 0;");
        var scene = frame.scene(sp);
        // The first measurement comes out short, before CSS has reached the wrapped labels, so once the
        // window is up it grows by whatever the laid-out content still needs, and again whenever the
        // content grows (an error line appearing), up to the screen's height.
        Runnable fit = () -> {
            var stage = frame.stage;
            var max = Screen.getPrimary().getVisualBounds().getHeight() * 0.94;
            var need = content.prefHeight(width) - sp.getViewportBounds().getHeight();
            var height = Math.min(max, stage.getHeight() + Math.max(0, need));
            if (Math.abs(height - stage.getHeight()) > 1) stage.setHeight(height);
        };
        frame.stage.addEventHandler(WindowEvent.WINDOW_SHOWN, e -> Platform.runLater(() -> Platform.runLater(fit)));
        content.heightProperty().addListener((o, was, now) -> Platform.runLater(fit));
        return scene;
    }

    private Look() {
    }
}

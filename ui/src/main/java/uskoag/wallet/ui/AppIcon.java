package uskoag.wallet.ui;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The icon set, light and dark, loaded from the jar so the shaded artifact stays self-contained:
 * {@code /icons/uskoag-wallet[-dark]-{16,24,32,48,256}.png}. The 16 is drawn pixel by pixel and is never
 * replaced by a scaled-down larger one. Missing sizes are skipped rather than fatal, because an icon is
 * never a reason for the wallet not to start.
 */
public final class AppIcon {

    static final int[] TRAY = {16, 24, 32}, WINDOW = {16, 32, 48, 256};

    private AppIcon() {
    }

    static String resource(boolean dark, int size) {
        return "/icons/uskoag-wallet" + (dark ? "-dark-" : "-") + size + ".png";
    }

    public static List<Image> images(boolean dark) {
        var out = new ArrayList<Image>();
        for (var size : WINDOW) {
            var in = AppIcon.class.getResourceAsStream(resource(dark, size));
            if (in != null) out.add(new Image(in));
        }
        return out;
    }

    /** The tray sizes as AWT images, smallest first; empty if none are in the jar. */
    static List<BufferedImage> trayImages(boolean dark) {
        var out = new ArrayList<BufferedImage>();
        for (var size : TRAY) {
            try (var in = AppIcon.class.getResourceAsStream(resource(dark, size))) {
                if (in != null) out.add(javax.imageio.ImageIO.read(in));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Sets the window's icons for the current taskbar theme and keeps them following it. */
    public static void applyTo(Stage stage) {
        stage.getIcons().setAll(images(Theme.isDark()));
        ChangeListener<Boolean> follow = (o, was, dark) -> stage.getIcons().setAll(images(dark));
        stage.getProperties().put(AppIcon.class, follow);   // the weak listener below lives as long as the stage
        Theme.darkProperty().addListener(new WeakChangeListener<>(follow));
    }
}

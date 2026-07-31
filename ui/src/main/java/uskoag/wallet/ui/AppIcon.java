package uskoag.wallet.ui;

import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * The icon set, loaded from the jar so the shaded artifact stays self-contained. Missing sizes are
 * skipped rather than fatal, because an icon is never a reason for the wallet not to start.
 */
public final class AppIcon {

    private static final String[] SIZES = {"16", "32", "48", "64", "128", "256"};

    private static List<Image> cached;

    private AppIcon() {
    }

    public static synchronized List<Image> images() {
        if (cached != null) return cached;
        var out = new ArrayList<Image>();
        for (var size : SIZES) {
            var in = AppIcon.class.getResourceAsStream("/icons/wallet-" + size + ".png");
            if (in != null) out.add(new Image(in));
        }
        cached = out;
        return out;
    }

    public static void applyTo(Stage stage) {
        var icons = images();
        if (!icons.isEmpty()) stage.getIcons().addAll(icons);
    }
}

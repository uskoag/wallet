package uskoag.wallet.ui;

import javafx.application.Platform;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * A tray presence so the wallet is reachable without leaving a window open.
 *
 * <p>AWT rather than JavaFX because JavaFX has no tray, and the icon is drawn here rather than shipped
 * as a resource so the shaded jar stays self-contained.
 */
public final class Tray {

    private static TrayIcon icon;

    private Tray() {
    }

    public static void install(Runnable onOpen, Runnable onLock) {
        if (!SystemTray.isSupported()) return;
        var menu = new PopupMenu();
        menu.add(item("Open wallet", onOpen));
        menu.add(item("Lock now", onLock));
        menu.addSeparator();
        menu.add(item("Quit", () -> {
            Platform.exit();
            System.exit(0);
        }));

        icon = new TrayIcon(trayImage(), "uskoag wallet", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> Platform.runLater(onOpen::run));
        try {
            SystemTray.getSystemTray().add(icon);
        } catch (AWTException ignored) {
        }
    }

    public static void note(String title, String message) {
        if (icon != null) icon.displayMessage(title, message, TrayIcon.MessageType.INFO);
    }

    private static MenuItem item(String text, Runnable action) {
        var m = new MenuItem(text);
        m.addActionListener(e -> Platform.runLater(action::run));
        return m;
    }

    /** The real icon when it is in the jar, and a drawn one when it is not — never a reason to fail. */
    private static Image trayImage() {
        try (var in = Tray.class.getResourceAsStream("/icons/wallet-32.png")) {
            if (in != null) return javax.imageio.ImageIO.read(in);
        } catch (Exception ignored) {
        }
        return dot();
    }

    private static Image dot() {
        var img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(0x1B, 0x5E, 0x20));
        g.fillRoundRect(1, 3, 14, 11, 4, 4);
        g.setColor(java.awt.Color.WHITE);
        g.fillOval(10, 7, 4, 4);
        g.dispose();
        return img;
    }
}

package uskoag.wallet.ui;

import javafx.application.Platform;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * A tray presence so the wallet is reachable without leaving a window open.
 *
 * <p>AWT rather than JavaFX because JavaFX has no tray. The icon comes from the jar (see AppIcon), with
 * the lock state drawn over it here.
 */
public final class Tray {

    private static TrayIcon icon;
    private static MenuItem lockItem, unlockItem;
    private static Boolean shownUnlocked;

    private Tray() {
    }

    /**
     * Reflects the actual lock state, in words and in the icon.
     *
     * <p>Until now the tray showed neither. One fixed dark-green icon and a tooltip that was only the
     * product name, whatever the wallet was doing — and a green light is read as ready. So a locked wallet
     * presented as an unlocked one, and "Lock now" was offered while it was already locked. Being told the
     * wrong state by the one thing on screen is worse than being told nothing, because it is acted on.
     *
     * <p>Polled rather than pushed, deliberately: the state changes from places the window never hears
     * about — {@code walletcli unlock}, {@code walletcli lock}, and the idle auto-lock, which is the one
     * that matters most because nobody is there when it fires.
     */
    public static void state(boolean unlocked, String detail) {
        if (icon == null) return;
        if (shownUnlocked != null && shownUnlocked == unlocked && detail == null) return;
        shownUnlocked = unlocked;
        icon.setToolTip(uskoag.wallet.wire.Brand.NAME + (unlocked ? " — UNLOCKED" : " — LOCKED")
                + (detail == null || detail.isBlank() ? "" : ": " + detail));
        icon.setImage(current());
        if (lockItem != null) lockItem.setEnabled(unlocked);
        if (unlockItem != null) unlockItem.setEnabled(!unlocked);
    }

    public static void install(Runnable onOpen, Runnable onUnlock, Runnable onLock, Runnable onRevokeAll,
                               Runnable onOpenAccess) {
        if (!SystemTray.isSupported()) {
            uskoag.wallet.daemon.Log.warn("SystemTray.isSupported() is false in this launch context - "
                    + "no tray icon will appear. On macOS this is usually a launchd session-type issue "
                    + "(agent not attached to the Aqua/GUI session), not a missing feature.");
            return;
        }
        uskoag.wallet.daemon.Log.info("SystemTray supported, installing tray icon");
        var menu = new PopupMenu();
        menu.add(item("Open wallet", onOpen));
        unlockItem = item("Unlock…", onUnlock);
        menu.add(unlockItem);
        lockItem = item("Lock now", onLock);
        menu.add(lockItem);
        menu.addSeparator();
        // The answer to "stop asking me", and it has to be HERE rather than on the approval dialog.
        // Somewhere to click when the interruption arrives is exactly what turns a bounded decision into a
        // reflex; the way out of being asked repeatedly should be a thing you go and do on purpose, from
        // the tray, with the passphrase in front of it. See OpenAccessWindow.
        menu.add(item("Open access for a while…", onOpenAccess));
        // Here as well as in the Permissions tab, because this is a thing worth doing casually and
        // periodically rather than deliberately: it only costs a click each to grant them again, and it
        // touches nothing at Google. An action whose worst case is "you approve a few documents again"
        // should be no further away than the one whose worst case is locking yourself out of a batch.
        // Directly under the item that opens access, because it is how you close it again.
        menu.add(item("Revoke all permissions", onRevokeAll));
        menu.addSeparator();
        menu.add(item("Quit", () -> {
            Platform.exit();
            System.exit(0);
        }));

        icon = new TrayIcon(current(), uskoag.wallet.wire.Brand.NAME, menu);
        icon.setImageAutoSize(true);
        // A dark taskbar wants the dark set; Theme re-reads the setting every two seconds.
        Theme.darkProperty().addListener((o, was, dark) -> icon.setImage(current()));
        icon.addActionListener(e -> Platform.runLater(onOpen::run));
        try {
            SystemTray.getSystemTray().add(icon);
            uskoag.wallet.daemon.Log.info("tray icon added");
        } catch (AWTException e) {
            uskoag.wallet.daemon.Log.error("SystemTray.add(icon) failed - tray reported supported but"
                    + " would not accept the icon", e);
        }
    }

    /**
     * The base icon with a state badge over it: green for unlocked, amber with a bar for locked.
     *
     * <p>A badge rather than a different picture, because the icon also has to stay recognisable as this
     * application in a tray of thirty. Amber and not red: locked is the safe state and the correct resting
     * state, not a fault.
     */
    /**
     * The tray picture for the current theme and lock state: 16, 24 and 32 px in one image, so Windows draws
     * the one that matches the display scaling (100% 16, 125% and 150% 24, 200% 32) rather than a blurred
     * rescale of one. Badged once the state is known.
     */
    private static Image current() {
        var bases = AppIcon.trayImages(Theme.isDark());
        if (bases.isEmpty()) bases = List.of(dot());
        var variants = new ArrayList<Image>();
        for (var base : bases) variants.add(shownUnlocked == null ? base : badged(base, shownUnlocked));
        return new BaseMultiResolutionImage(variants.toArray(new Image[0]));
    }

    /**
     * The state badge sits top-left, because the icon's own bottom-right corner carries the amber lease mark.
     */
    private static Image badged(BufferedImage base, boolean unlocked) {
        var w = base.getWidth();
        var h = base.getHeight();
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(base, 0, 0, w, h, null);
        if (!unlocked) {
            // Dimmed as well as badged, so the state survives being 16 pixels wide on a dark taskbar where
            // a small colour difference is not reliably visible.
            g.setColor(new java.awt.Color(0, 0, 0, 110));
            g.fillRect(0, 0, w, h);
        }
        var d = Math.max(6, w / 2);
        g.setColor(unlocked ? new java.awt.Color(0x2E, 0x7D, 0x32) : new java.awt.Color(0xE6, 0x8A, 0x00));
        g.fillOval(0, 0, d - 1, d - 1);
        g.setColor(java.awt.Color.WHITE);
        g.setStroke(new java.awt.BasicStroke(Math.max(1f, w / 16f)));
        if (unlocked) {
            g.drawLine(d / 4, d / 2, d / 2, d - d / 4 - 1);
            g.drawLine(d / 2, d - d / 4 - 1, d - d / 5, d / 5);
        } else {
            g.drawLine(d / 4, d / 2, d - d / 4 - 1, d / 2);
        }
        g.dispose();
        return img;
    }

    public static void note(String title, String message) {
        if (icon != null) icon.displayMessage(title, message, TrayIcon.MessageType.INFO);
    }

    /**
     * A menu item that says so when it fails, because the alternative is a click that does nothing.
     *
     * <p>{@code Platform.runLater} sends a throwing task to the FX thread's uncaught-exception handler,
     * which by default writes to a {@code stderr} that a GUI process does not have. So "Unlock…" and "Open
     * wallet" once failed in complete silence — no window, no balloon, no log line — under a wallet whose
     * jar had been replaced beneath it. {@link WalletApp} now installs a handler that logs, and this names
     * <em>which</em> item died, which the handler cannot know.
     */
    private static MenuItem item(String text, Runnable action) {
        var m = new MenuItem(text);
        m.addActionListener(e -> Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                uskoag.wallet.daemon.Log.error("tray item '" + text + "' failed", t);
                note(uskoag.wallet.wire.Brand.NAME, "'" + text + "' could not run: "
                        + t.getClass().getSimpleName() + ". If this wallet's jar was rebuilt while it was"
                        + " running, restart uskoag-wallet.exe. See wallet.log.");
            }
        }));
        return m;
    }

    /** Drawn stand-in when no icon is in the jar — never a reason to fail. */
    private static BufferedImage dot() {
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

package uskoag.wallet.ui;

import javafx.application.Platform;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import leasegate.frame.TaskbarTheme;
import leasegate.frame.TrayGlow;
import leasegate.tray.FxTray;
import leasegate.tray.TrayMenu;

import java.awt.SystemTray;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A tray presence so the wallet is reachable without leaving a window open.
 *
 * <p>The menu is drawn by JavaFX at the cursor, and the icon shows the state by its light rather than
 * by a badge: as drawn while locked, glowing in the wallet's colour while unlocked, glowing harder while
 * a rule that names no document is in force (the open-access window, {@code policy quiet}), with a short
 * pulse now and then so a wide-open wallet is hard to forget.
 */
public final class Tray {

    private static FxTray icon;
    private static volatile boolean unlocked, open;
    private static volatile String detail;
    private static String shown = "";
    private static int ticks;
    private static final Map<String, java.awt.Image> PICTURES = new HashMap<>();
    private static Actions actions;

    private record Actions(Runnable open, Runnable unlock, Runnable lock, Runnable revokeAll, Runnable openAccess,
                           Runnable changePassphrase) {
    }

    private Tray() {
    }

    /**
     * Reflects the actual state, in words and in the icon.
     *
     * <p>Polled rather than pushed, deliberately: the state changes from places the window never hears
     * about — {@code walletcli unlock}, {@code walletcli lock}, and the idle auto-lock, which is the one
     * that matters most because nobody is there when it fires.
     */
    public static void state(boolean unlockedNow, boolean openNow, String detailNow) {
        unlocked = unlockedNow;
        open = unlockedNow && openNow;
        detail = detailNow;
        if (icon == null) return;
        icon.tooltip(uskoag.wallet.wire.Brand.NAME + ": " + statusLine());
        showState();
    }

    static String statusLine() {
        var s = !unlocked ? "LOCKED" : open ? "UNLOCKED, open access in force" : "UNLOCKED";
        return detail == null || detail.isBlank() ? s : s + ": " + detail;
    }

    public static void install(Runnable onOpen, Runnable onUnlock, Runnable onLock, Runnable onRevokeAll,
                               Runnable onOpenAccess, Runnable onChangePassphrase) {
        if (!SystemTray.isSupported()) {
            uskoag.wallet.daemon.Log.warn("SystemTray.isSupported() is false in this launch context - "
                    + "no tray icon will appear. On macOS this is usually a launchd session-type issue "
                    + "(agent not attached to the Aqua/GUI session), not a missing feature.");
            return;
        }
        actions = new Actions(onOpen, onUnlock, onLock, onRevokeAll, onOpenAccess, onChangePassphrase);
        var menu = new TrayMenu(Look.BRAND.icon(), uskoag.wallet.wire.Brand.NAME, Look.COLOR, Tray::statusLine, Tray::menu);
        try {
            icon = FxTray.install(picture(TaskbarTheme.isDark(), TrayGlow.State.LOCKED, 0), uskoag.wallet.wire.Brand.NAME, menu,
                    () -> run("Open wallet", onOpen));
            uskoag.wallet.daemon.Log.info("tray icon added");
        } catch (RuntimeException e) {
            uskoag.wallet.daemon.Log.error("the tray icon could not be added", e);
            return;
        }
        // Once a second: follows the taskbar theme and the pulse. A background timer, not a Timeline: an
        // endless Timeline keeps JavaFX's frame clock running.
        Ui.TICKS.scheduleWithFixedDelay(() -> Platform.runLater(Tray::showState), 1, 1, TimeUnit.SECONDS);
    }

    /** Asked afresh each time the menu opens, so it always matches the state. */
    private static List<MenuItem> menu() {
        var m = new ArrayList<MenuItem>();
        m.add(item("Open wallet", actions.open));
        m.add(unlocked ? item("Lock now", actions.lock) : item("Unlock…", actions.unlock));
        m.add(new SeparatorMenuItem());
        // The answer to "stop asking me", and it has to be HERE rather than on the approval dialog: the way
        // out of being asked repeatedly should be a thing you go and do on purpose, with the passphrase in
        // front of it. See OpenAccessWindow.
        m.add(item("Open access for a while…", actions.openAccess));
        // Directly under the item that opens access, because it is how you close it again.
        m.add(item("Revoke all permissions", actions.revokeAll));
        m.add(new SeparatorMenuItem());
        m.add(item("Change passphrase…", actions.changePassphrase));
        m.add(new SeparatorMenuItem());
        m.add(item("Quit", () -> {
            Platform.exit();
            System.exit(0);
        }));
        return m;
    }

    /** Grey while locked, lit while unlocked, lasers round the edge while open access is in force. */
    private static synchronized void showState() {
        if (icon == null) return;
        var dark = TaskbarTheme.isDark();
        var state = open ? TrayGlow.State.OPEN : unlocked ? TrayGlow.State.ACTIVE : TrayGlow.State.LOCKED;
        var key = dark + ":" + state;
        if (key.equals(shown)) return;
        shown = key;
        if (laser != null) {
            laser.cancel(false);
            laser = null;
        }
        if (state == TrayGlow.State.OPEN) {
            laser = Ui.TICKS.scheduleAtFixedRate(() -> {
                ticks = (ticks + 1) % TrayGlow.FRAMES;
                icon.image(picture(dark, state, ticks));
            }, 0, TrayGlow.FRAME_MS, TimeUnit.MILLISECONDS);
        } else {
            icon.image(picture(dark, state, 0));
        }
    }

    private static java.util.concurrent.ScheduledFuture<?> laser;

    private static java.awt.Image picture(boolean dark, TrayGlow.State state, int frame) {
        synchronized (PICTURES) {
            return PICTURES.computeIfAbsent(dark + ":" + state + ":" + frame,
                    k -> TrayGlow.picture(Look.BRAND.icons().tray(dark), Look.COLOR, state, dark, frame));
        }
    }

    public static void note(String title, String message) {
        if (icon != null) icon.message(title, message);
    }

    /**
     * A menu item that says so when it fails, because the alternative is a click that does nothing.
     *
     * <p>A task that throws on the FX thread goes to the uncaught-exception handler, which by default
     * writes to a {@code stderr} that a GUI process does not have. So "Unlock…" and "Open wallet" once
     * failed in complete silence under a wallet whose jar had been replaced beneath it. {@link WalletApp}
     * installs a handler that logs, and this names <em>which</em> item died, which the handler cannot know.
     */
    private static MenuItem item(String text, Runnable action) {
        var m = new MenuItem(text);
        m.setMnemonicParsing(false);
        m.setOnAction(e -> run(text, action));
        return m;
    }

    private static void run(String text, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            uskoag.wallet.daemon.Log.error("tray item '" + text + "' failed", t);
            note(uskoag.wallet.wire.Brand.NAME, "'" + text + "' could not run: "
                    + t.getClass().getSimpleName() + ". If this wallet's jar was rebuilt while it was"
                    + " running, restart uskoag-wallet.exe. See wallet.log.");
        }
    }
}

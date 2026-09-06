package uskoag.wallet.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;
import uskoag.wallet.daemon.Debug;
import uskoag.wallet.daemon.Log;
import uskoag.wallet.daemon.WalletCore;
import uskoag.wallet.wire.Brand;
import uskoag.wallet.wire.HealthReport;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the daily credential check when it can, and asks for the passphrase when it cannot.
 *
 * <p>A one-minute timer rather than a single alarm at a chosen hour, and that is what makes the awkward
 * half of the requirement fall out for free. The check needs the keyring open, and whether it is open is
 * not something this can arrange — so instead of scheduling a moment and hoping, it watches for one.
 * Unlocked and overdue is the whole condition, so a sweep pending since breakfast runs the instant a tool
 * makes you unlock at noon, and a wallet restarted three times in an afternoon picks the job up at whichever
 * start finds it open.
 *
 * <p>It deliberately does not call {@code core.touch()}. A maintenance job that defers the auto-lock every
 * time it runs is a wallet that stops locking itself.
 */
public final class HealthDaily {

    /** Cheap: two field reads and, when a check is due, one small file. */
    private static final int TICK_SECONDS = 60;

    /** Long enough for the tray, the proxy and any startup unlock to settle first. */
    private static final int FIRST_TICK_SECONDS = 30;

    private static final AtomicBoolean running = new AtomicBoolean();

    private HealthDaily() {
    }

    public static void start(WalletCore core) {
        var timer = new Timeline(new KeyFrame(Duration.seconds(TICK_SECONDS), e -> tick(core)));
        timer.setCycleCount(Animation.INDEFINITE);
        timer.play();
        var first = new Timeline(new KeyFrame(Duration.seconds(FIRST_TICK_SECONDS), e -> tick(core)));
        first.play();
    }

    private static void tick(WalletCore core) {
        if (!core.settings.dailyHealthCheck || running.get()) return;
        if (!core.health.due()) return;
        if (core.keyring.unlocked()) {
            sweep(core);
            return;
        }
        // A debug run is unattended by definition and never re-unlocks, so a passphrase box here would sit
        // in front of nobody until it timed out. Same reasoning as FxGateway.unlockNeeded.
        if (Debug.on()) return;
        if (!core.health.shouldAskForPassphrase()) return;
        core.health.asked();
        UnlockWindow.show(core, () -> sweep(core),
                UnlockAsk.forHealthCheck(core.settings.healthPromptMinutes));
    }

    /**
     * Runs the sweep off the FX thread, because it is several network round trips per credential and the
     * one thread that must never be waiting on Google is the one drawing the windows.
     */
    static void sweep(WalletCore core) {
        if (!running.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                report(core.health.run(null, null));
            } catch (Throwable t) {
                // Locking mid-sweep lands here, and it is not an error worth a balloon: the check is simply
                // due again, and the next unlock will pick it up.
                Log.warn("daily credential check did not finish: " + t);
            } finally {
                running.set(false);
            }
        }, "wallet-health").start();
    }

    /**
     * Says what was found where it will be seen.
     *
     * <p>The sweep almost never runs while anyone is looking at the Accounts tab, so a column that has
     * quietly turned red is a column nobody reads. A balloon for a failure and silence for a clean run:
     * a daily "all fine" notification is a notification that gets turned off, and then the one that matters
     * is off with it.
     */
    private static void report(HealthReport report) {
        Platform.runLater(() -> {
            if (!report.allWell()) Tray.note(Brand.NAME, report.headline());
            AccountsPane.refreshIfShowing();
        });
    }
}

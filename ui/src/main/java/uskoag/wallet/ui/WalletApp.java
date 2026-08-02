package uskoag.wallet.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import uskoag.wallet.daemon.AlreadyRunning;
import uskoag.wallet.daemon.Debug;
import uskoag.wallet.daemon.Log;
import uskoag.wallet.daemon.Wallet;

/**
 * uskoag-wallet.exe. The only process on this machine that ever holds a refresh token.
 *
 * <p>The engine runs in-process rather than in a separate daemon, because an approval dialog that lives
 * somewhere other than the thing needing the answer is an approval that cannot be given. Closing the
 * window hides it; the wallet keeps serving from the tray.
 */
public final class WalletApp extends Application {

    private final Wallet wallet = new Wallet();

    @Override
    public void start(Stage ignored) throws Exception {
        Platform.setImplicitExit(false);
        wallet.core.gateway(new FxGateway(wallet.core));
        try {
            wallet.start();
        } catch (AlreadyRunning e) {
            // Exit rather than compete, and do it without a dialog: two wallets would publish to one
            // handshake file, so clients would talk to the newest while the oldest still held the
            // keyring and the audit lock. A modal box here would also leave this process alive waiting
            // for a click, which is the very thing being prevented.
            Log.warn(e.getMessage());
            raiseTheOtherOne();
            Platform.exit();
            System.exit(0);
            return;
        }

        Tray.install(this::open, this::unlock, this::lock, this::revokeAll);
        Tray.state(wallet.core.keyring.unlocked(), null);

        // Polled, because the lock state changes from places this process never hears about: walletcli
        // lock and unlock, and the idle auto-lock — which is the one that matters, since by definition
        // nobody is watching when it fires. Five seconds is far below the granularity anyone notices and
        // the check is a field read.
        var watch = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.seconds(5),
                e -> Tray.state(wallet.core.keyring.unlocked(), null)));
        watch.setCycleCount(javafx.animation.Animation.INDEFINITE);
        watch.play();

        // Nothing on screen at startup, by default. The main window was appearing on every launch and
        // being closed again immediately, which is a step added to a thing that runs at boot; and the
        // passphrase is not asked for until something actually needs it, so starting the wallet costs
        // nothing. Both windows are one tray click, or one more launch of the exe, away.
        var args = getParameters().getRaw();

        // Development mode, before anything else can put a dialog on screen. Startup only: it
        // deliberately does not re-unlock later, because a wallet that unlocks itself on demand has no
        // lock, and because locking mid-run is one of the behaviours that needs testing.
        var debugPass = Debug.arm(args.contains("--debug"));
        if (debugPass != null) {
            try {
                wallet.core.unlock(debugPass);
                Tray.note(uskoag.wallet.wire.Brand.NAME, "DEBUG MODE — unlocked from the environment,"
                        + " approvals answered without asking. Not for real work.");
            } catch (Exception e) {
                Log.error("--debug could not unlock; check " + Debug.PASSPHRASE_VAR, e);
            } finally {
                java.util.Arrays.fill(debugPass, '\0');
            }
            return;
        }

        if (args.contains("--show")) {
            UnlockWindow.show(wallet.core, () -> MainWindow.show(wallet.core), null);
        } else if (args.contains("--unlock")) {
            UnlockWindow.show(wallet.core, () -> Tray.note(uskoag.wallet.wire.Brand.NAME,
                    "Unlocked. Tools on this machine can now reach Google through it."), null);
        } else {
            Tray.note(uskoag.wallet.wire.Brand.NAME, "Running, and locked. The passphrase is asked for when a tool"
                    + " first needs it.");
        }
    }

    /** What someone starting an already-running app actually wants: its window, not a warning. */
    private static void raiseTheOtherOne() {
        try {
            uskoag.wallet.wire.WalletClient.ifRunning()
                    .ifPresent(c -> {
                        try {
                            c.callRaw("show", java.util.Map.of());
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    /**
     * Drops every standing permission. Safe by construction, and worth saying why so it stays that way:
     * it removes approvals, never consents. Google keeps every grant the accounts were given, so nothing
     * has to be re-authorised in a browser — each document simply asks once more the next time it is
     * touched. The confirmation exists to say that, not to discourage doing it.
     */
    private void revokeAll() {
        if (!wallet.core.keyring.unlocked()) {
            Tray.note(uskoag.wallet.wire.Brand.NAME, "Locked, so there is nothing live to revoke."
                    + " Standing permissions are already unusable until it is unlocked.");
            return;
        }
        try {
            var n = wallet.core.policy.rules().size();
            if (n == 0) {
                Tray.note(uskoag.wallet.wire.Brand.NAME, "No standing permissions to revoke.");
                return;
            }
            if (!Confirm.ask("Revoke all permissions",
                    "Revoke all " + n + " standing permission(s)?\n\n"
                            + "Every tool goes back to asking on first touch, which costs one click each.\n\n"
                            + "Nothing is revoked at Google. The accounts keep every consent they were"
                            + " given, so no browser sign-in is needed again.",
                    "Revoke all " + n, "Keep them")) {
                return;
            }
            wallet.core.policy.clear();
            MainWindow.hide();
            Tray.note(uskoag.wallet.wire.Brand.NAME, n + " permission(s) revoked. Google's grants are"
                    + " untouched.");
        } catch (Exception e) {
            Tray.note(uskoag.wallet.wire.Brand.NAME, "Could not revoke: " + e.getMessage());
        }
    }

    /**
     * The tray's "Open wallet", and what it does depends on the lock state.
     *
     * <p>It used to call {@link MainWindow#show} unconditionally, so opening a locked wallet produced the
     * main window over a keyring it could not read: every tab empty, and the Accounts tab stating "No
     * accounts yet" — which of a credential store reads as the credentials having been destroyed. The
     * window has nothing to show and nothing to do until the passphrase is in, so asking for it first is
     * both the honest order and the one that leads somewhere.
     */
    private void open() {
        if (wallet.core.keyring.unlocked()) {
            MainWindow.show(wallet.core);
            return;
        }
        UnlockWindow.show(wallet.core, () -> {
            Tray.state(true, null);
            MainWindow.show(wallet.core);
        }, "Locked — the passphrase is needed before there is anything to show.");
    }

    /** Tray "Unlock…", for when the window is not what is wanted, only the passphrase. */
    private void unlock() {
        if (wallet.core.keyring.unlocked()) {
            Tray.note(uskoag.wallet.wire.Brand.NAME, "Already unlocked.");
            return;
        }
        UnlockWindow.show(wallet.core, () -> {
            Tray.state(true, null);
            Tray.note(uskoag.wallet.wire.Brand.NAME,
                    "Unlocked. Tools on this machine can now reach Google through it.");
        }, null);
    }

    private void lock() {
        wallet.core.lock();
        MainWindow.hide();
        Tray.state(false, null);
        Tray.note(uskoag.wallet.wire.Brand.NAME, "Locked. Everything in flight has stopped.");
    }

    @Override
    public void stop() {
        Log.info("shutting down");
        wallet.stop();
    }
}

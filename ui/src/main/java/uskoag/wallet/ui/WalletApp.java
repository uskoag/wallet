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
        logFxFailures();
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

        Tray.install(this::open, this::unlock, this::lock, this::revokeAll, this::openAccess);
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

        // The daily readonly credential check. Started here rather than inside the engine because the two
        // things it needs when the keyring is shut — a passphrase box, and someone to see it — only exist
        // in this module. It watches for an unlocked wallet instead of scheduling an hour, so it runs at
        // whatever moment the wallet happens to be open, including one somebody else caused.
        HealthDaily.start(wallet.core);

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
            UnlockWindow.show(wallet.core, () -> MainWindow.show(wallet.core), UnlockAsk.forTool(null));
        } else if (args.contains("--unlock")) {
            UnlockWindow.show(wallet.core, () -> Tray.note(uskoag.wallet.wire.Brand.NAME,
                    "Unlocked. Tools on this machine can now reach Google through it."),
                    UnlockAsk.forTool(null));
        } else {
            Tray.note(uskoag.wallet.wire.Brand.NAME, "Running, and locked. The passphrase is asked for when a tool"
                    + " first needs it.");
        }
    }

    /**
     * Anything that dies on the FX thread has to reach the log, because otherwise it reaches nowhere.
     *
     * <p><b>Written after this cost twenty minutes of diagnosis on a wallet that looked healthy.</b> A jar
     * had been replaced under a running wallet — the documented failure — and the resulting symptom was not
     * any of the ones already written down. The control port answered, {@code status} was correct, the FX
     * and AWT threads were both alive and idle, and the log's last line was an ordinary auto-lock. The
     * tray's "Unlock…" and "Open wallet" simply did nothing: no window, no message, no trace. It took
     * enumerating the process's top-level Win32 windows to establish that no unlock stage was ever created.
     *
     * <p>The reason is structural rather than incidental. {@code Platform.runLater} hands a throwing task
     * to the FX thread's uncaught-exception handler, whose default prints to {@code stderr} — and
     * {@code uskoag-wallet.exe} is a GUI process with no console attached, so stderr is discarded. Every
     * failure inside every one of this application's windows was therefore invisible by construction, and
     * the more central the window, the more completely it failed in silence.
     *
     * <p>Both handlers, deliberately. The FX thread's own covers the windows; the default covers the proxy
     * and control worker threads, which have the same missing sink. The tray balloon is part of the fix and
     * not decoration: the log is the right record, but somebody who has just clicked a menu item that did
     * nothing needs to be told that on screen, where they are looking.
     */
    private static void logFxFailures() {
        Thread.UncaughtExceptionHandler handler = (thread, error) -> {
            Log.error("unhandled failure on " + thread.getName()
                    + " — if a window failed to appear, this is why", error);
            // A rebuilt jar under a running wallet is by far the most common cause, so name it rather than
            // making the next person rediscover it from a stack trace.
            var linkage = error instanceof LinkageError
                    || error.getCause() instanceof LinkageError;
            try {
                Tray.note(uskoag.wallet.wire.Brand.NAME, (linkage
                        ? "Its own code has been replaced underneath it — restart uskoag-wallet.exe. "
                        : "") + "Something failed: " + error.getClass().getSimpleName()
                        + ". See wallet.log.");
            } catch (Throwable ignored) {
                // The handler must never throw. Failing to report a failure is bad; turning it into a
                // second failure inside the reporter is how a process stops answering altogether.
            }
        };
        Thread.currentThread().setUncaughtExceptionHandler(handler);
        Thread.setDefaultUncaughtExceptionHandler(handler);
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

    /**
     * Tray "Open access for a while…". Refreshes the main window if it happens to be up, so the new rule
     * is visible in the Permissions tab immediately rather than at the next manual refresh — the whole
     * argument for this being a rule rather than a mode is that it can be seen and revoked.
     */
    private void openAccess() {
        OpenAccessWindow.show(wallet.core, () -> {
            if (MainWindow.isShowing()) MainWindow.show(wallet.core);
        });
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
        }, UnlockAsk.forTool(null));
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

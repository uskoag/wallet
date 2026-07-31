package uskoag.wallet.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import uskoag.wallet.daemon.AlreadyRunning;
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

        Tray.install(() -> MainWindow.show(wallet.core), this::lock);
        UnlockWindow.show(wallet.core, () -> {
            MainWindow.show(wallet.core);
            Tray.note("uskoag wallet", "Unlocked. Tools on this machine can now reach Google through it.");
        }, null);
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

    private void lock() {
        wallet.core.lock();
        MainWindow.hide();
        Tray.note("uskoag wallet", "Locked. Everything in flight has stopped.");
        UnlockWindow.show(wallet.core, () -> MainWindow.show(wallet.core), "Locked from the tray.");
    }

    @Override
    public void stop() {
        Log.info("shutting down");
        wallet.stop();
    }
}

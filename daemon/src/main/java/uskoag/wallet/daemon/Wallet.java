package uskoag.wallet.daemon;

import java.io.IOException;

/**
 * Boots the engine: proxy first so its port is known, then the control server that publishes both.
 *
 * <p>The UI drives this in-process. Two processes would mean an approval dialog living somewhere other
 * than the thing that needs the answer, and an approval nobody can show is an approval nobody can give.
 */
public final class Wallet {

    public final WalletCore core = new WalletCore();

    private final Proxy proxy = new Proxy(core);
    private final ControlServer control = new ControlServer(core);

    /**
     * @throws AlreadyRunning when another wallet already owns this home. Starting anyway would give
     *                        both processes the same handshake file and split the audit between them.
     */
    public void start() throws IOException {
        if (!SingleInstance.claim()) {
            throw new AlreadyRunning("another uskoag-wallet already owns " + uskoag.wallet.wire.WalletPaths.home()
                    + " (pid " + SingleInstance.holder() + "). Use that one, or set UKAG_WALLET_HOME"
                    + " to run a separate wallet.");
        }
        var proxyPort = proxy.start();
        control.start(proxyPort);
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
        // Idle almost all day: two quiet minutes, then one collection and the working set handed back.
        leasegate.core.IdleTrim.start("uskoag-wallet", core::lastActivity, java.time.Duration.ofMinutes(2));
        if (!Dpapi.available()) {
            Log.warn("DPAPI is not available on this machine — the keyring falls back to passphrase-only"
                    + " encryption, which is still safe at rest but is no longer inert if copied elsewhere");
        }
    }

    public void stop() {
        try {
            control.stop();
            proxy.stop();
            core.lock();
        } catch (Exception ignored) {
        } finally {
            SingleInstance.release();
        }
    }
}

package uskoag.wallet.wire;

import uskoag.gservices.AccessSpec;
import uskoag.gservices.CredentialSource;
import uskoag.gservices.ServiceAccess;

import java.io.IOException;

/**
 * The wallet half of the gservices seam, found by {@link java.util.ServiceLoader} when this jar is on
 * a tool's classpath and simply absent otherwise.
 *
 * <p>What the tool gets back is a handle and a loopback root URL. It never sees a refresh token, a
 * client secret or a Google access token at any moment, which is the property the whole design exists
 * for.
 */
public final class WalletCredentialSource implements CredentialSource {

    @Override
    public String name() {
        return "wallet";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean available() {
        return WalletClient.ifRunning().isPresent() || WalletLauncher.walletJar().isPresent();
    }

    /**
     * Running and unlocked, asked without disturbing anyone.
     *
     * <p>Deliberately does not start a wallet that is not running, and deliberately does not ask a
     * locked one for anything: both would put a window in front of whoever happens to be at the machine
     * on behalf of a caller that said it was unattended. A wallet that cannot be reached answers no.
     */
    @Override
    public boolean ready() {
        try {
            return WalletClient.ifRunning()
                    .map(c -> {
                        try {
                            return c.status().unlocked();
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ServiceAccess access(AccessSpec spec) throws IOException {
        var client = WalletLauncher.ensureRunning().orElseThrow(() -> new IOException(
                "no wallet is running and none could be started — run uskoag-wallet, or install it and retry"));

        var grant = request(client, spec);
        if (!grant.ok()) throw new IOException("wallet refused access: " + grant.error());
        announce(grant);

        var init = new GrantInitializer(grant.grant(), () -> {
            try {
                var fresh = request(client, spec);
                return fresh.ok() ? fresh.grant() : null;
            } catch (Exception e) {
                return null;
            }
        });
        return new ServiceAccess(init, grant.rootUrl(), grant.account(), name());
    }

    private static AccessGrant request(WalletClient client, AccessSpec spec) throws IOException {
        try {
            return client.access(new AccessRequest(
                    spec.api(), spec.profile(), spec.appName(), spec.account(), spec.scopes(),
                    SessionId.current(), ProcessHandle.current().pid(), SessionId.peerCommand()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while asking the wallet for access", e);
        }
    }

    /**
     * One line to stderr, so that when four agent runs are in flight and a dialog says "a tool wants to
     * delete 340 files", the question of which one is answerable.
     */
    private static void announce(AccessGrant grant) {
        if (System.getenv("UKAG_WALLET_QUIET") != null) return;
        System.err.println("wallet approval code: " + grant.correlationCode() + "  (" + grant.account() + ")");
    }
}

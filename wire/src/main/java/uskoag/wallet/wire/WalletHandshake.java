package uskoag.wallet.wire;

/**
 * What a running wallet publishes so clients can find it.
 *
 * <p>The token authenticates the loopback channel, nothing more: it is not a credential and unlocks
 * no account. Security comes from the file's ACL, the wallet being unlocked, and the approval.
 */
public record WalletHandshake(
        String version,
        int controlPort,
        int proxyPort,
        String token,
        long pid,
        long startedAt) {

    public String controlBase() {
        return "http://127.0.0.1:" + controlPort + "/wallet/";
    }

    public String proxyBase() {
        return "http://127.0.0.1:" + proxyPort + "/g/";
    }
}

package uskoag.wallet.daemon;

/**
 * A handle the wallet issued and the wallet alone can redeem. Never a Google token, so a client that
 * points itself at googleapis.com by mistake gets a 401 rather than an unlogged, unpoliced success.
 */
public record Grant(
        String token,
        String account,
        String profile,
        String appName,
        String api,
        String session,
        String correlationCode,
        long pid,
        String peerCommand,
        long issuedAt) {
}

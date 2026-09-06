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
        String sessionLabel,
        String correlationCode,
        long pid,
        uskoag.wallet.wire.CallerInfo caller,
        long issuedAt) {

    /**
     * {@code session} is identity and {@code sessionLabel} is words, and keeping them apart is the whole
     * repair. Identity is the anchor the wallet resolved from the kernel — what a session-bound rule
     * matches on, and never anything a client chose. The label is what a person reads in the dialog and
     * the audit: the name the run exported, and the process it was pinned to.
     */
    public String sessionOrLabel() {
        return sessionLabel == null || sessionLabel.isBlank() ? session : sessionLabel;
    }
}

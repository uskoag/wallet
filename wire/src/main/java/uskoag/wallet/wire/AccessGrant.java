package uskoag.wallet.wire;

/**
 * What comes back: an opaque handle and a local root URL, never a Google token.
 *
 * <p>The handle is worthless anywhere except against this wallet, which is the property that makes
 * the policy a control rather than a suggestion — there is no second route to Google.
 */
public record AccessGrant(
        String grant,
        String rootUrl,
        String account,
        String correlationCode,
        long expiresAt,
        String error) {

    public boolean ok() {
        return error == null && grant != null;
    }

    public static AccessGrant failed(String why) {
        return new AccessGrant(null, null, null, null, 0L, why);
    }
}

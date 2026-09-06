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
        String error,
        String warning) {

    public boolean ok() {
        return error == null && grant != null;
    }

    public static AccessGrant failed(String why) {
        return new AccessGrant(null, null, null, null, 0L, why, null);
    }

    /**
     * Something the caller should be told even though the call succeeded.
     *
     * <p>It exists for one case in particular: a run that declared a source process which turned out not
     * to be an ancestor of the process actually calling. The wallet logs that, but the wallet's log is
     * read afterwards and by then the question has gone cold — whereas the client's stderr is read now, by
     * whoever or whatever is running the command and is the only party able to fix it.
     */
    public AccessGrant withWarning(String note) {
        return note == null || note.isBlank() ? this
                : new AccessGrant(grant, rootUrl, account, correlationCode, expiresAt, error, note);
    }
}

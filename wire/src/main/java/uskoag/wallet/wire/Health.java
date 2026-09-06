package uskoag.wallet.wire;

/**
 * What the daily readonly ping found out about one credential.
 *
 * <p>Seven verdicts rather than a boolean, because the cure differs and only the verdict knows which one
 * it is: a dead consent is re-authenticated, a rejected OAuth client needs a fresh
 * {@code credentials.json} on the org, and no network needs nothing at all. Collapsing them into
 * "unhealthy" would put the same useless sentence in front of all three.
 */
public enum Health {

    UNKNOWN("not checked yet", "not checked", false),

    HEALTHY("healthy", "healthy", false),

    /**
     * The refresh token is gone: revoked, expired, or the account's password changed. Also what a
     * Testing-status OAuth client produces every seven days, on the dot — see {@link OrgInfo#expiryHint}.
     */
    STALE("STALE — re-authenticate", "STALE", true),

    /** The token still lives but Google no longer honours a scope it was granted. */
    SCOPE_LOST("scope withdrawn", "scope gone", true),

    /**
     * Google refused the OAuth client itself. The org's {@code credentials.json} has been deleted or
     * disabled at the Cloud project, so re-consenting the account cannot help — the client has to be
     * replaced first, and every account under it is in the same position.
     */
    CLIENT_GONE("OAUTH CLIENT REJECTED", "CLIENT GONE", false),

    /** Google answered, and refused for a reason that is not this credential: API disabled, quota. */
    BLOCKED("blocked by Google", "blocked", false),

    /**
     * Nothing could be reached. Never treated as a failure of the credential: an aeroplane is not a
     * revoked grant, so this leaves {@code lastHealthyAt} and {@code staleSince} exactly as they were.
     */
    UNREACHABLE("could not reach Google", "unreachable", false);

    public final String label;

    /**
     * The column form, which is a different job from {@link #label} and needs a different length.
     *
     * <p>Learned by photographing the table: at any width the column can afford, "STALE — re-authenticate"
     * and "OAUTH CLIENT REJECTED" both ellipsise — so the two verdicts that most need reading were the two
     * being cut off, and the advice half was the part surviving. The advice belongs in the banner, the
     * tooltip and the detail box, all of which have room for it; the column owes the reader the state and
     * how long it has been that way, and nothing else.
     */
    public final String brief;

    /** True when a browser consent is the fix, which is what the Accounts tab's R key offers. */
    public final boolean needsReauth;

    Health(String label, String brief, boolean needsReauth) {
        this.label = label;
        this.brief = brief;
        this.needsReauth = needsReauth;
    }

    /** Worth showing in red and counting in the banner. {@link #UNREACHABLE} is not a fault. */
    public boolean bad() {
        return this == STALE || this == SCOPE_LOST || this == CLIENT_GONE;
    }
}

package uskoag.wallet.daemon;

import java.util.ArrayList;
import java.util.List;

/**
 * One token: one account, one scope group, one consent, one expiry.
 *
 * <p>Several of these per account is the whole point. Google expires refresh tokens for unverified apps
 * per token, so a single union token dies when its shortest-lived scope does — an unused mail grant
 * would otherwise take Sheets, Docs and Slides down with it.
 *
 * <p>{@code scopes} is what this token was actually granted, never a union with anything else. A union
 * describes no real token, and claiming one means failing later on an opaque 403.
 */
public final class CredentialRecord {

    String account, orgId, group, refreshToken;
    List<String> scopes = new ArrayList<>();
    long addedAt, lastUsed;
    long useCount;

    /** Lower wins among tokens that all satisfy a request. Seeded by narrowness, reorderable by hand. */
    int order;

    /**
     * What the daily readonly ping last found, and when.
     *
     * <p>Kept apart from {@code lastUsed} and {@code useCount} deliberately. The ping is not work: routing
     * it through {@link TokenCache} — which is one line shorter — would set {@code lastUsed} on every
     * token every day, and "which of these consents does this office not actually need" would stop being
     * an answerable question. The two clocks measure different things and they stay separate.
     *
     * <p>{@code staleSince} is left at 0 while the credential is healthy and set once when it first fails,
     * so it answers "dead since when" rather than "dead". Nothing here is touched by an
     * {@link uskoag.wallet.wire.Health#UNREACHABLE} result: a laptop off the network is not a revoked
     * grant, and recording it as one would fabricate an outage that never happened.
     */
    uskoag.wallet.wire.Health health = uskoag.wallet.wire.Health.UNKNOWN;
    String healthNote;
    long lastCheckedAt, lastHealthyAt, staleSince;

    public uskoag.wallet.wire.Health health() {
        return health == null ? uskoag.wallet.wire.Health.UNKNOWN : health;
    }

    /**
     * Records a verdict. Returns true when the state changed, which is what decides whether the audit and
     * the tray hear about it — a healthy credential found healthy for the four hundredth time is not news.
     */
    boolean healthIs(uskoag.wallet.wire.Health found, String note, long now) {
        var was = health();
        health = found;
        healthNote = note;
        lastCheckedAt = now;
        if (found == uskoag.wallet.wire.Health.HEALTHY) {
            lastHealthyAt = now;
            staleSince = 0;
        } else if (found.bad() && staleSince == 0) {
            staleSince = now;
        }
        return was != found;
    }

    /** How long this credential had lived when it died, in days. Zero while it is alive or unknown. */
    long lifeDaysAtDeath() {
        return staleSince <= 0 || addedAt <= 0 ? 0 : Math.max(0, (staleSince - addedAt) / 86_400_000L);
    }

    public CredentialRecord() {
    }

    public CredentialRecord(String account, String orgId, String group) {
        this.account = account;
        this.orgId = orgId;
        this.group = group;
        this.addedAt = System.currentTimeMillis();
    }

    public String account() {
        return account;
    }

    public String group() {
        return group;
    }

    public String key() {
        return account + "|" + group;
    }

    public List<String> scopes() {
        if (scopes == null) scopes = new ArrayList<>();
        return scopes;
    }

    public boolean covers(List<String> wanted) {
        return !wanted.isEmpty() && scopes().containsAll(wanted);
    }

    public boolean coversAny(List<String> alternatives) {
        return alternatives.stream().anyMatch(s -> scopes().contains(s));
    }

    public void used() {
        lastUsed = System.currentTimeMillis();
        useCount++;
    }
}

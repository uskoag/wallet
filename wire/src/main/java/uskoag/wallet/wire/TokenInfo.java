package uskoag.wallet.wire;

import java.util.List;

/**
 * One token as the UI and CLI see it. The refresh token itself is never in here.
 *
 * @param order         preference among tokens that all satisfy a request; lower wins
 * @param useCount      total requests served, so a token nobody uses is visible and removable
 * @param health        what the daily readonly ping last found. Deliberately separate from
 *                      {@code useCount} and {@code lastUsed}: the ping is not work, and letting it move
 *                      those two would make every token look busy and quietly destroy the one report
 *                      that answers which consents this office does not actually need.
 * @param lastHealthyAt the answer to "when was this last known to work", which is the column asked for
 * @param staleSince    when it first failed, so "dead since Tuesday" is answerable rather than "dead"
 */
public record TokenInfo(
        String account,
        String group,
        String label,
        String detail,
        List<String> scopes,
        Tier2 tier,
        long addedAt,
        long lastUsed,
        long useCount,
        int order,
        Health health,
        String healthNote,
        long lastCheckedAt,
        long lastHealthyAt,
        long staleSince) {

    /** 1.2K, 3.4M — a raw 1483920 tells you nothing at a glance. */
    public static String count(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return round(n / 1000d) + "K";
        if (n < 1_000_000_000L) return round(n / 1_000_000d) + "M";
        return round(n / 1_000_000_000d) + "B";
    }

    private static String round(double v) {
        return v >= 10 ? String.valueOf(Math.round(v)) : String.valueOf(Math.round(v * 10) / 10d);
    }

    public boolean neverUsed() {
        return useCount == 0;
    }

    /** Null-safe, because a keyring written before health existed has no value stored for it. */
    public Health state() {
        return health == null ? Health.UNKNOWN : health;
    }

    /**
     * How many days this credential has lived, or had lived when it died.
     *
     * <p>The number that separates a Testing-status OAuth client from an ordinary revocation: seven, over
     * and over, is not a coincidence.
     */
    public long lifeDays() {
        var end = staleSince > 0 ? staleSince : System.currentTimeMillis();
        return addedAt <= 0 ? 0 : Math.max(0, (end - addedAt) / 86_400_000L);
    }

    /** One row of the copyable TSV, so an inventory can be pasted straight into a sheet. */
    public String tsv() {
        return String.join("\t", account, group, label, tier.label,
                String.valueOf(useCount), String.valueOf(lastUsed),
                state().name(), String.valueOf(lastHealthyAt), String.valueOf(staleSince),
                healthNote == null ? "" : healthNote.replace('\t', ' '),
                String.join(" ", scopes));
    }

    public static String tsvHeader() {
        return String.join("\t", "account", "group", "label", "tier", "uses", "lastUsedEpochMs",
                "health", "lastHealthyEpochMs", "staleSinceEpochMs", "healthNote", "scopes");
    }
}

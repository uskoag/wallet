package uskoag.wallet.wire;

import java.util.List;

/**
 * One token as the UI and CLI see it. The refresh token itself is never in here.
 *
 * @param order    preference among tokens that all satisfy a request; lower wins
 * @param useCount total requests served, so a token nobody uses is visible and removable
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
        int order) {

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

    /** One row of the copyable TSV, so an inventory can be pasted straight into a sheet. */
    public String tsv() {
        return String.join("\t", account, group, label, tier.label,
                String.valueOf(useCount), String.valueOf(lastUsed), String.join(" ", scopes));
    }

    public static String tsvHeader() {
        return String.join("\t", "account", "group", "label", "tier", "uses", "lastUsedEpochMs", "scopes");
    }
}

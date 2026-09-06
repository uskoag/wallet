package uskoag.wallet.wire;

/**
 * One credential's verdict, with the sentence that says what to do about it.
 *
 * @param note        Google's own words where there were any, because a paraphrase of an OAuth error is
 *                    how a diagnosis gets lost
 * @param probed      which APIs were actually called, so a pass is readable as a pass of something
 * @param lifeDays    how long this credential had lived when it went stale, or since it was granted while
 *                    it is alive. The number that tells a Testing-status client from a real revocation.
 */
public record HealthRow(String account, String group, String label, Health health,
                        String note, String probed, long checkedAt, long healthyAt,
                        long staleSince, long lifeDays) {
}

package uskoag.wallet.wire;

import java.util.List;

/**
 * One organisation's OAuth client, as seen from outside the wallet. The secret never appears here.
 *
 * @param owner          the account this client was created under, if known. Advisory: a client in Testing
 *                       status only lets listed test users consent, so granting a different account under
 *                       it often fails in a way Google explains badly.
 * @param lastExercisedAt when the wallet last put this client's id and secret in front of Google's token
 *                       endpoint. It matters because Google deletes an OAuth client that has been
 *                       inactive for six months — {@code credentials.json} itself is thrown away, not
 *                       merely the tokens minted from it — and the daily readonly ping is what keeps this
 *                       moving.
 * @param observedLifeDays how many days each credential under this client had lived when it went stale,
 *                       most recent last. The diagnosis, not decoration — see {@link #expiryHint}.
 */
public record OrgInfo(String id, String label, String clientId, String owner,
                      List<String> domains, int accounts, long addedAt,
                      long lastExercisedAt, List<Integer> observedLifeDays) {

    /** Google deletes an OAuth client unused for this long, credentials.json and all. */
    public static final int RETIRED_AFTER_DAYS = 180;

    public List<Integer> lives() {
        return observedLifeDays == null ? List.of() : observedLifeDays;
    }

    public long daysSinceExercised() {
        return lastExercisedAt <= 0 ? -1 : (System.currentTimeMillis() - lastExercisedAt) / 86_400_000L;
    }

    /**
     * What the observed token lifetimes say about this client, in the words that fix it.
     *
     * <p>The whole reason the wallet records lifetimes at all. A refresh token issued by a Cloud project
     * still in <em>Testing</em> publishing status expires seven days after it was issued — not on
     * idleness, on age — and no amount of pinging prevents that. Nothing in any API reports the publishing
     * status, so it has to be inferred from behaviour, and the behaviour is unmistakable once two or three
     * deaths have been recorded at the same number of days.
     *
     * @return null when there is nothing worth saying yet
     */
    public String expiryHint() {
        var lives = lives();
        if (lives.size() < 2) return null;
        var recent = lives.subList(Math.max(0, lives.size() - 4), lives.size());
        var worst = recent.stream().mapToInt(Integer::intValue).max().orElse(0);
        if (worst > 14) return null;
        return "credentials under this client have died after " + join(recent) + " day(s)."
                + " A Cloud project still in Testing expires every refresh token 7 days after it is"
                + " issued, whatever you do with it. Publish the app — Cloud console, OAuth consent"
                + " screen, Publish app — and they stop expiring on a clock.";
    }

    /** Approaching the six-month client deletion, which the daily ping exists to prevent. */
    public String retirementWarning() {
        var days = daysSinceExercised();
        if (days < 0) return "never exercised by this wallet";
        if (days < RETIRED_AFTER_DAYS - 60) return null;
        return "not exercised for " + days + " day(s); Google deletes an OAuth client after "
                + RETIRED_AFTER_DAYS + " days of inactivity, credentials.json included";
    }

    private static String join(List<Integer> values) {
        return values.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("");
    }
}

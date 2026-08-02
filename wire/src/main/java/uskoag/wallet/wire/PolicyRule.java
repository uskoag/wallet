package uskoag.wallet.wire;

/**
 * One standing permission: this tool, on this account, may do up to this much to this resource, until
 * this expiry or this many operations, whichever comes first.
 *
 * <p>This is the generalisation of the per-spreadsheet allow-list that used to live in each CLI's own
 * XML. Keeping it per-tool meant every tool grew its own half of a policy engine and none of them
 * could see a Drive file and a Sheet as the same object; keeping it here means one place decides, one
 * place expires, and one place is audited.
 *
 * <p>Counts before time, deliberately. A time window bounds a human's session and does not bound a
 * machine's: a runaway loop issues ten thousand operations inside a fifteen-minute grant and every one
 * of them is inside what was approved.
 */
public final class PolicyRule {

    /**
     * {@code session} null means the rule stands across runs — the approved-document list. A non-null
     * value binds it to one run of work, which is what a destructive approval gets, so saying yes to
     * one agent run never silently authorises the next one.
     */
    public String id, profile, account, api, resource, note, session;

    /**
     * The resource's human name as it read at the moment of approval, kept so that a rule listing and an
     * audit line say "Quarterly Tracker" rather than an id nobody can place. Advisory and possibly stale:
     * the id is what {@link #applies} matches on, never this.
     */
    public String label;

    public Tier tier = Tier.READ;
    public Match match = Match.EXACT;
    public long expiresAt, createdAt, lastUsed;
    public int opsBudget = -1, opsUsed;

    public PolicyRule() {
    }

    public boolean expired(long now) {
        return expiresAt > 0 && now >= expiresAt;
    }

    public boolean exhausted() {
        return opsBudget >= 0 && opsUsed >= opsBudget;
    }

    public boolean live(long now) {
        return !expired(now) && !exhausted();
    }

    public boolean applies(String reqProfile, String reqAccount, String reqApi, String reqResource, String reqSession) {
        return wild(profile, reqProfile) && wild(account, reqAccount) && wild(api, reqApi)
                && (session == null || session.equals(reqSession))
                && (resource == null || match.test(resource, reqResource));
    }

    private static boolean wild(String ruleValue, String actual) {
        return ruleValue == null || "*".equals(ruleValue) || ruleValue.equalsIgnoreCase(actual);
    }

    /** True for a blanket rule: no api and no resource, so it covers everything at its tier. */
    public boolean blanket() {
        return api == null && resource == null;
    }

    public String describe() {
        // Spelled out rather than left as "* / * / * / *", because a listing is read to decide what to
        // revoke, and four asterisks are easy to skim past as "unset" when they mean the opposite.
        if (blanket()) {
            return tier + "  EVERYTHING on " + (account == null ? "every account" : account)
                    + "  [" + (opsBudget >= 0 ? (opsBudget - opsUsed) + " ops left" : "unlimited ops")
                    + ", " + until() + "]";
        }
        var named = label == null || label.isBlank()
                ? (resource == null ? "*" : resource)
                : label + " (" + resource + ")";
        var scope = (profile == null ? "*" : profile) + " / " + (account == null ? "*" : account)
                + " / " + (api == null ? "*" : api) + " / " + named;
        var limit = opsBudget >= 0 ? (opsBudget - opsUsed) + " ops left" : "unlimited ops";
        return tier + "  " + scope + "  [" + limit + ", " + until() + "]";
    }

    /** Local time, not an instant: a rule listing is read by a person deciding whether to revoke it. */
    public String until() {
        if (expiresAt <= 0) return "no expiry";
        var when = java.time.Instant.ofEpochMilli(expiresAt).atZone(java.time.ZoneId.systemDefault());
        return "until " + when.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }
}

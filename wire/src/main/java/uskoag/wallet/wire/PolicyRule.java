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

    public String describe() {
        var scope = (profile == null ? "*" : profile) + " / " + (account == null ? "*" : account)
                + " / " + (api == null ? "*" : api) + " / " + (resource == null ? "*" : resource);
        var limit = opsBudget >= 0 ? (opsBudget - opsUsed) + " ops left" : "unlimited ops";
        var until = expiresAt > 0 ? "until " + java.time.Instant.ofEpochMilli(expiresAt) : "no expiry";
        return tier + "  " + scope + "  [" + limit + ", " + until + "]";
    }
}

package uskoag.wallet.daemon;

import java.util.ArrayList;
import java.util.List;

/**
 * One OAuth client, which in practice means one organisation.
 *
 * <p>A {@code credentials.json} is a Cloud project's client, not a tool's and not a person's. One per
 * org is not a preference, it is what Google leaves available: an unverified app hits the hundred-user
 * cap and the unverified-app screen, and a Workspace admin can refuse a foreign client ID outright
 * through App Access Control. So the client secret belongs here, once, and every account in the org
 * mints its tokens from it.
 *
 * <p>{@code domains} exists so nobody has to name the org by hand — an address matching one of them
 * resolves to this record. It is a list of patterns, not one string: a single client routinely serves
 * several domains, and {@link uskoag.wallet.wire.DomainRule} allows exact, wildcard and regex forms
 * because no fixed shape survives contact with real organisations.
 */
public final class OrgRecord {

    /**
     * {@code owner} is the account this client was created under, recorded from the first successful
     * consent. It is not a permission check — it is a warning surface. An OAuth client in Testing status
     * only lets accounts on its test-user list consent, so a client made under one Gmail account
     * routinely fails for another with an error that says nothing useful. Recording the owner lets the
     * wallet say "this client belongs to X, you are granting Y" before the browser opens, rather than
     * leaving you to interpret Google's refusal.
     */
    String id, label, credentialsJson, clientId, clientSecret, owner;
    List<String> domains = new ArrayList<>();
    long addedAt = System.currentTimeMillis();

    /**
     * When this client's id and secret were last put in front of Google's token endpoint.
     *
     * <p>Recorded because Google deletes an OAuth client that has been inactive for six months, and what
     * it deletes is the client — {@code credentials.json} itself, not merely the tokens minted from it. So
     * an org whose accounts all sit idle loses the thing that cannot be re-created by consenting again.
     * The daily readonly ping is what keeps this number moving, and this is where that is visible.
     */
    long lastExercisedAt;

    /**
     * How many days each credential under this client had lived when it went stale, oldest first, capped.
     *
     * <p>This is the only route to a fact Google publishes nowhere: a Cloud project still in
     * <em>Testing</em> publishing status expires every refresh token seven days after issuing it,
     * regardless of use. No API reports the publishing status, so it is inferred from the pattern of
     * deaths — and the pattern is unmistakable once two or three have been recorded at the same figure.
     * See {@link uskoag.wallet.wire.OrgInfo#expiryHint}.
     */
    List<Integer> observedLifeDays = new ArrayList<>();

    private static final int KEPT_LIVES = 8;

    public List<Integer> observedLifeDays() {
        if (observedLifeDays == null) observedLifeDays = new ArrayList<>();
        return observedLifeDays;
    }

    void exercised(long now) {
        lastExercisedAt = now;
    }

    /** One credential under this client has died after {@code days}. Kept as evidence, not as a total. */
    void died(long days) {
        if (days <= 0) return;
        observedLifeDays().add((int) days);
        while (observedLifeDays().size() > KEPT_LIVES) observedLifeDays().removeFirst();
    }

    public OrgRecord() {
    }

    public OrgRecord(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public List<String> domains() {
        if (domains == null) domains = new ArrayList<>();
        return domains;
    }

    public boolean covers(String email) {
        return uskoag.wallet.wire.DomainRule.matches(domains(), email);
    }

    /** Remembers the domain of an account added under this org, so the next one needs no flag. */
    public void learn(String email) {
        if (email == null) return;
        var at = email.indexOf('@');
        if (at < 0) return;
        var domain = email.substring(at + 1).toLowerCase();
        if (!covers(email) && !domains().contains(domain)) domains().add(domain);
    }

    /** Replaces the pattern list wholesale, dropping blanks. Editing is how a second domain arrives. */
    public void setDomains(java.util.List<String> patterns) {
        domains().clear();
        if (patterns == null) return;
        patterns.stream().map(String::trim).filter(s -> !s.isEmpty())
                .forEach(s -> domains().add(s.toLowerCase()));
    }
}

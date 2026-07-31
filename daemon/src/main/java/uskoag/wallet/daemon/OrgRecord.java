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

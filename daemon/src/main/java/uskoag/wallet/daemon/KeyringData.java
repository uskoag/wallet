package uskoag.wallet.daemon;

import uskoag.wallet.wire.PolicyRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the wallet holds, as one plaintext object that only ever exists in memory.
 *
 * <p>Three levels, because they are three different things: an {@link OrgRecord} owns the OAuth client,
 * a {@link CredentialRecord} owns one account's refresh token, and a rule owns one standing permission.
 * Collapsing any two of them is what made the first version awkward to import into.
 *
 * <p>The policy rules live here beside the credentials rather than in a config file, because a list of
 * the documents this office works on is itself a map of the office — arguably more sensitive than the
 * tokens, since a stolen token gets revoked and a disclosed list of matters cannot be un-disclosed.
 *
 * <p>{@code auditKeyB64} is the audit's own column key, sealed under the wallet passphrase. Keeping it
 * here rather than deriving it from the passphrase is what makes changing the passphrase a re-encrypt
 * of one small file instead of a migration of a year of history.
 */
public final class KeyringData {

    /**
     * Bumped whenever the stored shape changes. {@link Keyring#unlock} refuses a mismatch rather than
     * letting Gson parse an older shape into this one and leave the moved fields unset.
     */
    public static final String VERSION = "4";

    String version = VERSION;
    long createdAt = System.currentTimeMillis();
    String auditKeyB64;
    List<OrgRecord> orgs = new ArrayList<>();
    List<CredentialRecord> credentials = new ArrayList<>();
    List<PolicyRule> rules = new ArrayList<>();

    public List<OrgRecord> orgs() {
        if (orgs == null) orgs = new ArrayList<>();
        return orgs;
    }

    public List<CredentialRecord> credentials() {
        if (credentials == null) credentials = new ArrayList<>();
        return credentials;
    }

    public List<PolicyRule> rules() {
        if (rules == null) rules = new ArrayList<>();
        return rules;
    }
}

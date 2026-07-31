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

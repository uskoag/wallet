package uskoag.wallet.daemon;

import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a refresh token into a live access token, and keeps it until shortly before it expires.
 *
 * <p>This is the only place in the system where a Google access token exists, and it never leaves the
 * process: the proxy reads it to write one header. The client is never given one at any moment, which
 * is what makes the tier rules a control rather than a request.
 */
public final class TokenCache {

    private static final long EARLY_MS = 120_000;

    private final Map<String, Live> cache = new ConcurrentHashMap<>();
    private final NetHttpTransport transport = new NetHttpTransport();

    /**
     * The client half comes from the org, because that is where an OAuth client actually lives.
     *
     * <p><b>Keyed by the individual credential, not by the account.</b> It used to be keyed on the email
     * alone, and that quietly undid the entire point of picking a token per request. An account here
     * holds six of them — docs, drive.file, drive.readonly, drive full, mail read, mail write — so
     * whichever one happened to mint first was handed to every later call for that account until it
     * expired, whatever {@link TokenPicker} had chosen.
     *
     * <p>It failed in both directions, and the dangerous one was silent. Visibly: a request needing full
     * {@code drive} received an access token minted from {@code drive.readonly} and came back
     * ACCESS_TOKEN_SCOPE_INSUFFICIENT — confusing, but at least loud. Invisibly: an ordinary read was
     * served by the full-control token, so "the narrowest sufficient token wins and the wide one stays
     * cold" was not true of the traffic, only of the decision — and the audit recorded the narrow choice
     * either way, which is the worst part. A record that says something safer happened than did is worse
     * than no record.
     *
     * <p>The group is part of the key because it is what identifies the scope set; the org because the
     * same address can exist under two OAuth clients and their access tokens are not interchangeable.
     */
    public String accessToken(CredentialRecord cred, OrgRecord org) throws IOException {
        var key = cred.account + "|" + cred.orgId + "|" + cred.group;
        var live = cache.get(key);
        if (live != null && live.usableAt(System.currentTimeMillis() + EARLY_MS)) return live.token();
        synchronized (this) {
            live = cache.get(key);
            if (live != null && live.usableAt(System.currentTimeMillis() + EARLY_MS)) return live.token();
            var fresh = refresh(cred, org);
            cache.put(key, fresh);
            return fresh.token();
        }
    }

    private Live refresh(CredentialRecord cred, OrgRecord org) throws IOException {
        if (cred.refreshToken == null || cred.refreshToken.isBlank()) {
            throw new IOException("no refresh token for " + cred.account
                    + " - run: uskoag-walletcli login " + cred.account);
        }
        if (org == null || org.clientSecret == null) {
            throw new IOException("no OAuth client stored for org '" + cred.orgId + "' - run:"
                    + " uskoag-walletcli org add " + cred.orgId + " --file credentials.json");
        }
        var req = new GoogleRefreshTokenRequest(transport, GsonFactory.getDefaultInstance(),
                cred.refreshToken, org.clientId, org.clientSecret);
        var res = req.execute();
        var ttl = res.getExpiresInSeconds() == null ? 3600 : res.getExpiresInSeconds();
        cred.lastUsed = System.currentTimeMillis();
        return new Live(res.getAccessToken(), System.currentTimeMillis() + ttl * 1000);
    }

    public void clear() {
        cache.clear();
    }
}

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

    /** The client half comes from the org, because that is where an OAuth client actually lives. */
    public String accessToken(CredentialRecord cred, OrgRecord org) throws IOException {
        var key = cred.account;
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

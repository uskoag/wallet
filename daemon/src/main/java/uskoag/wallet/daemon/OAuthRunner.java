package uskoag.wallet.daemon;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.MemoryDataStoreFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

/**
 * The consent flow, run by the wallet and by nothing else.
 *
 * <p>This is structural rather than a convenience: the refresh token is born in the consent response,
 * so a client that ran the flow would hold one at least once and the whole guarantee would be gone
 * before it started. The token store here is in memory, so nothing is written to disk except the
 * keyring the caller then saves.
 *
 * <p>The client comes from the org, never from the tool. One OAuth client serves every account and
 * every tool in that org, which is both what Google's client model actually is and the only shape that
 * survives an unverified app's hundred-user cap.
 */
public final class OAuthRunner {

    private OAuthRunner() {
    }

    public static CredentialRecord consent(String account, OrgRecord org,
                                           uskoag.wallet.wire.ScopeGroup group, int port) throws IOException {
        return consent(account, org, group, port, new HeadlessGateway(), true);
    }

    public static CredentialRecord consent(String account, OrgRecord org, uskoag.wallet.wire.ScopeGroup group,
                                           int port, ApprovalGateway gateway, boolean openBrowser)
            throws IOException {
        var scopes = group.scopes();
        var secrets = GoogleClientSecrets.load(GsonFactory.getDefaultInstance(),
                new StringReader(org.credentialsJson));
        var flow = new GoogleAuthorizationCodeFlow.Builder(
                new NetHttpTransport(), GsonFactory.getDefaultInstance(), secrets, scopes)
                .setDataStoreFactory(MemoryDataStoreFactory.getDefaultInstance())
                .setAccessType("offline")
                .setApprovalPrompt("force")
                .build();

        var receiver = new LocalServerReceiver.Builder().setPort(port).build();
        // authorize() is keyed per user id in the store; keying it per group too keeps two consents in
        // the same run from colliding in the in-memory store and returning each other's credential.
        var credential = new ConsentApp(flow, receiver, gateway, account, openBrowser)
                .authorize(account + "|" + group.id());
        return record(account, org.id, group.id(), scopes, credential);
    }

    static CredentialRecord record(String account, String orgId, String group, List<String> scopes,
                                   Credential credential) throws IOException {
        if (credential.getRefreshToken() == null) {
            throw new IOException("Google returned no refresh token for " + account
                    + " - revoke the app at myaccount.google.com and consent again");
        }
        var r = new CredentialRecord(account, orgId, group);
        r.refreshToken = credential.getRefreshToken();
        r.scopes = List.copyOf(scopes);
        return r;
    }
}

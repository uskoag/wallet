package uskoag.wallet.daemon;

import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver;

import java.io.IOException;

/**
 * The consent flow, with the URL surfaced instead of being swallowed by whichever browser Windows
 * happens to consider default.
 *
 * <p>Google's helper opens the default browser and nothing else. When that browser is signed in as a
 * different Google account — routine here, with several orgs on one machine — the consent lands in the
 * wrong session and the URL is stranded inside a window it cannot easily be copied out of. Handing the
 * text to the gateway first turns that from a dead end into a paste.
 *
 * <p>Auto-opening still happens by default, since it usually is the right browser; it is one setting
 * away from being off for anyone whose default never is.
 */
public final class ConsentApp extends AuthorizationCodeInstalledApp {

    private final ApprovalGateway gateway;
    private final String account;
    private final boolean openBrowser;

    public ConsentApp(AuthorizationCodeFlow flow, VerificationCodeReceiver receiver,
                      ApprovalGateway gateway, String account, boolean openBrowser) {
        super(flow, receiver);
        this.gateway = gateway;
        this.account = account;
        this.openBrowser = openBrowser;
    }

    @Override
    protected void onAuthorization(AuthorizationCodeRequestUrl authorizationUrl) throws IOException {
        var url = authorizationUrl.build();
        // Shown before the browse attempt, so the copyable text is already on screen if the browser
        // opens somewhere useless — or does not open at all.
        gateway.authUrl(account, url);
        if (openBrowser) {
            try {
                browse(url);
            } catch (RuntimeException e) {
                Log.warn("could not open a browser automatically - copy the URL from the window instead");
            }
        }
    }
}

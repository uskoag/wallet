package uskoag.wallet.wire;

import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Puts the wallet handle on every outgoing request, and nothing else.
 *
 * <p>No {@code Authorization} header is set at all, deliberately: if a client ever points itself at
 * googleapis.com by mistake the call fails with 401 rather than leaking a usable credential, because
 * the handle is worthless anywhere except against this wallet.
 */
public final class GrantInitializer implements HttpRequestInitializer {

    public static final String HEADER = "X-Wallet-Grant";

    private final Supplier<String> reacquire;
    private volatile String grant;

    public GrantInitializer(String grant, Supplier<String> reacquire) {
        this.grant = grant;
        this.reacquire = reacquire;
    }

    @Override
    public void initialize(HttpRequest request) throws IOException {
        request.getHeaders().set(HEADER, grant);
        request.setConnectTimeout(60_000);
        request.setReadTimeout(300_000);
        request.setUnsuccessfulResponseHandler((req, res, supportsRetry) -> {
            if (!supportsRetry || res.getStatusCode() != 401) return false;
            var fresh = reacquire.get();
            if (fresh == null || fresh.equals(grant)) return false;
            grant = fresh;
            req.getHeaders().set(HEADER, fresh);
            return true;
        });
    }
}

package uskoag.wallet.daemon;

import uskoag.wallet.wire.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * What Google says an access token is actually allowed to do, asked of Google rather than assumed.
 *
 * <p>This exists because Sheets, Docs and Slides sell no read that does not name a document. Every other
 * API here has a cheap listing or profile call that proves a token still works; those three have
 * {@code get} and nothing else, so the first attempt probed a document id that deliberately did not exist
 * and read the 404 as a pass. That worked, and it was still the wrong idea: it put two extra network round
 * trips on the one credential that needs none, and in use the third of them hit a connect timeout and
 * condemned a perfectly healthy credential. A probe whose failure mode is a false alarm is a probe that
 * teaches people to ignore the check.
 *
 * <p>{@code tokeninfo} is better on every count. It requires <b>no scope at all</b>, so one call covers any
 * token whatever it carries; it answers the question directly rather than by inference, returning the exact
 * scope list Google will honour; and that makes a withdrawn scope nameable instead of guessed at from a 403.
 *
 * <p><b>POST, with the token in the body.</b> Google documents the GET form with {@code ?access_token=},
 * and that would put a live access token in a URL — the one shape guaranteed to end up in a log somewhere.
 * The POST form is accepted (measured) and keeps it out of every URL. Nothing here is ever logged: not the
 * token, not the request, not the body.
 */
public final class GrantedScopes {

    private static final String ENDPOINT = "https://oauth2.googleapis.com/tokeninfo";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private GrantedScopes() {
    }

    /**
     * @return every scope Google reports for this token, never null
     * @throws IOException when Google could not be reached, or answered something unreadable. Deliberately
     *                     not distinguished from a network failure by the caller: an access token minted
     *                     seconds ago cannot legitimately be refused here, so anything but an answer means
     *                     the question did not get through.
     */
    /**
     * Retried once, for the same reason {@link Probes} retries: connect timeouts to Google's hosts are not
     * rare on this machine — one was caught in the act while testing this very method — and for a
     * Sheets/Docs/Slides token this is now the only call besides the refresh, so a single hiccup would leave
     * the scopes unverified for the day.
     */
    public static List<String> of(String accessToken) throws IOException {
        try {
            return ask(accessToken);
        } catch (IOException first) {
            return ask(accessToken);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> ask(String accessToken) throws IOException {
        try {
            var res = HTTP.send(HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "access_token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IOException("tokeninfo answered HTTP " + res.statusCode());
            }
            var body = Json.to(res.body(), Map.class);
            var scope = body == null ? null : body.get("scope");
            if (scope == null) throw new IOException("tokeninfo returned no scope list");
            return List.of(String.valueOf(scope).trim().split("\\s+"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while asking Google which scopes this token carries");
        } catch (RuntimeException e) {
            throw new IOException(Why.of(e));
        }
    }

    /** Stored scopes Google no longer reports. Named, because "scope withdrawn" alone fixes nothing. */
    public static List<String> missingFrom(List<String> granted, List<String> claimed) {
        return claimed == null ? List.of()
                : claimed.stream().filter(s -> s != null && !s.isBlank() && !granted.contains(s)).toList();
    }
}

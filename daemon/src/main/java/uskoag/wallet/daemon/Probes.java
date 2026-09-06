package uskoag.wallet.daemon;

import uskoag.wallet.wire.Health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Runs one health probe, and retries once before giving up on it.
 *
 * <p>The retry is the whole reason this is its own class. In use the first version reported a healthy
 * credential as unreachable on a single {@code HttpConnectTimeoutException} — one hiccup, on one of three
 * calls, at 20:06 on the first real sweep. Sixteen credentials times a handful of probes a day is enough
 * round trips that the rare failure is not rare; a check that converts one into a red row has a false alarm
 * rate, and a check with a false alarm rate stops being read.
 *
 * <p>One retry, not three: the point is to absorb a hiccup, not to insist. Anything that fails twice is
 * reported as not reached — and {@link HealthCheck} does not hold that against the credential either, since
 * the refresh has already proved the consent is alive.
 */
public final class Probes {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private Probes() {
    }

    public static ProbeOutcome run(HealthProbe probe, String bearer) {
        var first = once(probe, bearer);
        if (first.reached() || first.verdict() != null) return first;
        var again = once(probe, bearer);
        return again.reached() || again.verdict() != null ? again
                : new ProbeOutcome(false, null, again.note() + ", twice");
    }

    private static ProbeOutcome once(HealthProbe probe, String bearer) {
        try {
            var res = HTTP.send(HttpRequest.newBuilder(URI.create(probe.url()))
                    .header("Authorization", "Bearer " + bearer)
                    .timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (probe.passed(res.statusCode())) return new ProbeOutcome(true, null, "");
            return new ProbeOutcome(true, verdictFor(res.statusCode(), res.body()),
                    probe.api() + " refused it: HTTP " + res.statusCode() + " " + firstLine(res.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeOutcome(false, null, "interrupted");
        } catch (Exception e) {
            return new ProbeOutcome(false, null, Why.of(e));
        }
    }

    /**
     * What one refusal says about the credential.
     *
     * <p>A 403 that names insufficient scope is about the token. Any other 403 — a disabled API, an
     * exhausted quota — is about the project, and calling it {@code BLOCKED} rather than a credential
     * failure is the difference between "enable the API" and "re-authenticate", which are not the same job.
     */
    private static Health verdictFor(int status, String body) {
        if (status == 401) return Health.STALE;
        if (status == 403) {
            var b = body == null ? "" : body;
            return b.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT") || b.contains("insufficientPermissions")
                    ? Health.SCOPE_LOST : Health.BLOCKED;
        }
        return Health.BLOCKED;
    }

    private static String firstLine(String body) {
        if (body == null || body.isBlank()) return "";
        var line = body.strip().replaceAll("\\s+", " ");
        return line.length() > 180 ? line.substring(0, 179) + "…" : line;
    }
}

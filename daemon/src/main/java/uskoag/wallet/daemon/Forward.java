package uskoag.wallet.daemon;

import com.sun.net.httpserver.HttpExchange;
import uskoag.wallet.wire.GApi;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The byte pipe. Nothing here parses, decodes or logs a body.
 *
 * <p>Speed is a design requirement, not an aspiration: uploads and downloads are routine work here and
 * must stay within a whisker of going direct. The bottleneck on a download is the internet link at
 * roughly 10 MB/s while Windows loopback moves gigabytes per second, so this hop sits about a hundred
 * times above the constraint and can only become the constraint by being written badly. Written badly
 * means small buffers, buffering instead of streaming, and a fresh connection per call — so: 256KB
 * buffers, streamed both ways, and one pooled client that keeps TLS to Google warm across the hundreds
 * of short-lived CLI processes an agent run spawns. That last part is why this is likely to be faster
 * than today rather than slower.
 */
public final class Forward {

    private static final int BUFFER = 256 * 1024;

    private static final HttpClient UPSTREAM = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private Forward() {
    }

    public static void relay(HttpExchange x, GApi api, String path, String query,
                             byte[] bufferedBody, String bearer, int proxyPort) throws IOException {
        var uri = URI.create(api.upstream + path + (query == null || query.isBlank() ? "" : "?" + query));
        var b = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(30));

        for (var e : x.getRequestHeaders().entrySet()) {
            if (Hop.skipOutbound(e.getKey())) continue;
            for (var v : e.getValue()) b.header(e.getKey(), v);
        }
        b.header("Authorization", "Bearer " + bearer);

        var method = x.getRequestMethod();
        var body = bufferedBody != null
                ? HttpRequest.BodyPublishers.ofByteArray(bufferedBody)
                : hasBody(x) ? HttpRequest.BodyPublishers.ofInputStream(x::getRequestBody)
                             : HttpRequest.BodyPublishers.noBody();
        b.method(method, body);

        HttpResponse<InputStream> res;
        try {
            res = UPSTREAM.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while forwarding to Google", e);
        }

        var out = x.getResponseHeaders();
        res.headers().map().forEach((name, values) -> {
            if (Hop.skipInbound(name)) return;
            for (var v : values) out.add(name, Rewrite.inbound(v, proxyPort));
        });

        var empty = res.statusCode() == 204 || res.statusCode() == 304 || "HEAD".equalsIgnoreCase(method);
        x.sendResponseHeaders(res.statusCode(), empty ? -1 : 0);
        if (empty) {
            x.close();
            return;
        }
        try (var in = res.body(); var sink = x.getResponseBody()) {
            var buf = new byte[BUFFER];
            for (var n = in.read(buf); n > 0; n = in.read(buf)) sink.write(buf, 0, n);
        }
    }

    private static boolean hasBody(HttpExchange x) {
        var m = x.getRequestMethod();
        return !("GET".equalsIgnoreCase(m) || "HEAD".equalsIgnoreCase(m) || "DELETE".equalsIgnoreCase(m));
    }
}

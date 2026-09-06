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
                             Bodies bodies, String bearer, int proxyPort) throws IOException {
        var uri = URI.create(api.upstream + path + (query == null || query.isBlank() ? "" : "?" + query));
        var b = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(30));

        for (var e : x.getRequestHeaders().entrySet()) {
            if (Hop.skipOutbound(e.getKey())) continue;
            for (var v : e.getValue()) b.header(e.getKey(), v);
        }
        b.header("Authorization", "Bearer " + bearer);

        var method = x.getRequestMethod();
        b.method(method, publisher(x, bodies));

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

    /**
     * The three ways a body reaches Google, and the middle one exists because of a bug worth naming.
     *
     * <p>A body that was buffered whole is sent as bytes. A body that turned out to be larger than the
     * parse limit has had its first megabyte consumed already and cannot be rewound, so the consumed
     * prefix is put back in front of the rest of the stream — where the old code simply forwarded the
     * truncated prefix on its own. Nothing caught it because a gzipped body is chunked and carries no
     * Content-Length, so the guard that was supposed to stream large bodies never fired for the ones that
     * mattered. Everything else streams as before.
     */
    private static HttpRequest.BodyPublisher publisher(HttpExchange x, Bodies bodies) {
        if (bodies.buffered() != null) return HttpRequest.BodyPublishers.ofByteArray(bodies.buffered());
        if (bodies.prefix() != null) {
            return HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.SequenceInputStream(
                    new java.io.ByteArrayInputStream(bodies.prefix()), x.getRequestBody()));
        }
        return hasBody(x) ? HttpRequest.BodyPublishers.ofInputStream(x::getRequestBody)
                          : HttpRequest.BodyPublishers.noBody();
    }

    private static boolean hasBody(HttpExchange x) {
        var m = x.getRequestMethod();
        return !("GET".equalsIgnoreCase(m) || "HEAD".equalsIgnoreCase(m) || "DELETE".equalsIgnoreCase(m));
    }
}

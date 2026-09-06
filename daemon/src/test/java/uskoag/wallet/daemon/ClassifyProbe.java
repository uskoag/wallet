package uskoag.wallet.daemon;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

/**
 * What the classifier now sees, driven through a real HTTP exchange so the body arrives the way a Google
 * client actually sends it — gzipped, chunked, no Content-Length.
 *
 * <p>Not a JUnit test: it prints, and the point is to read the words a person would be shown. Run it with
 * the daemon and wire classes on the classpath; it needs no wallet, no keyring and no network.
 */
public final class ClassifyProbe {

    private static int failures;

    public static void main(String[] args) throws Exception {
        var seen = new AtomicReference<Classification>();
        var buffered = new java.util.concurrent.atomic.AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/", x -> {
            try {
                var api = x.getRequestURI().getPath().substring(1).split("/", 2)[0];
                var rest = x.getRequestURI().getPath().substring(1 + api.length());
                var body = Bodies.of(x, 1 << 20, BatchEnvelope.isEnvelope(rest));
                buffered.set(body.buffered() != null);
                seen.set(Rules.classify(new RequestFacts(api, x.getRequestMethod(), rest,
                        x.getRequestURI().getRawQuery(), body.readable())));
                x.sendResponseHeaders(204, -1);
            } catch (Exception e) {
                x.sendResponseHeaders(500, -1);
            } finally {
                x.close();
            }
        });
        server.start();
        var root = "http://127.0.0.1:" + server.getAddress().getPort();

        expect(post(root, "/sheets/v4/spreadsheets/SS1:batchUpdate", json(
                "{\"requests\":[{\"addSheet\":{\"properties\":{\"title\":\"ByteIdenticalRejects\"}}}]}"), seen),
                "MUTATE", "batch update — 1 request (addSheet)", 1);

        expect(post(root, "/sheets/v4/spreadsheets/SS1:batchUpdate", json(
                "{\"requests\":[{\"addSheet\":{}},{\"updateCells\":{}},{\"deleteSheet\":{}}]}"), seen),
                "DESTRUCTIVE", "batch update — 1 of 3 requests delete (deleteSheet)", 3);

        expect(post(root, "/sheets/v4/spreadsheets/SS1:batchUpdate", json(
                "{\"requests\":[{\"a\":{}},{\"b\":{}},{\"c\":{}},{\"d\":{}},{\"e\":{}},{\"f\":{}}]}"), seen),
                "MUTATE", "batch update — 6 requests (a, b, c, d, +2 more)", 6);

        // The Sheets shapes, taken from what the real client emits (see prp/04-prp.04). Reading N ranges
        // is a GET with repeated query params, not a batch body; writing and clearing N ranges are POSTs
        // whose scale is only visible inside the body.
        expect(get(root, "/sheets/v4/spreadsheets/SS1/values:batchGet",
                "ranges=Sheet1!A1:C10&ranges=Sheet2!A:D&ranges=Notes!A1:B5", seen),
                "READ", "read", 1);

        expect(get(root, "/sheets/v4/spreadsheets/SS1", "includeGridData=true&ranges=Sheet1!A1:C10", seen),
                "READ", "read", 1);

        expect(post(root, "/sheets/v4/spreadsheets/SS1/values:batchUpdate", json(
                "{\"data\":[{\"range\":\"Sheet1!A1:C10\"},{\"range\":\"Sheet2!A1:D9\"},"
                + "{\"range\":\"Notes!A1:B5\"}],\"valueInputOption\":\"RAW\"}"), seen),
                "MUTATE", "write cells in 3 ranges", 3);

        expect(post(root, "/sheets/v4/spreadsheets/SS1/values:batchClear",
                json("{\"ranges\":[\"Sheet1!A1:C10\",\"Notes!A1:B5\"]}"), seen),
                "MUTATE", "clear 2 ranges", 2);

        expect(post(root, "/sheets/v4/spreadsheets/SS1/values:batchClear",
                json("{\"ranges\":[\"Sheet1!A1:C10\",\"Sheet2!A:D\",\"Notes!A1:B5\"]}"), seen),
                "DESTRUCTIVE", "clear 3 ranges, 1 of them unbounded (Sheet2!A:D)", 3);

        // Ordinary single-range writes, on a spread of range spellings.
        for (var r : new String[]{"Sheet1!A1", "Sheet1!B2:D9", "'Odd Name'!A1:A1",
                                  "Notes!AA100:AC220", "Sheet1!A1:C10"}) {
            expect(put(root, "/sheets/v4/spreadsheets/SS1/values/"
                    + java.net.URLEncoder.encode(r, StandardCharsets.UTF_8),
                    "valueInputOption=RAW", json("{\"values\":[[\"x\"]]}"), seen),
                    "MUTATE", "write cells", 1);
        }

        expect(post(root, "/sheets/v4/spreadsheets/SS1/values/Sheet1!A1:append",
                json("{\"values\":[[\"x\"]]}"), seen), "MUTATE", "write cells", 1);

        // A single clear is judged on the range in the URL: bounded is a write, unbounded is not.
        expect(post(root, "/sheets/v4/spreadsheets/SS1/values/Sheet1!A1:C10:clear", json("{}"), seen),
                "MUTATE", "clear Sheet1!A1:C10", 1);
        // A bare sheet name is the whole sheet, whatever digits are in its own name.
        for (var s : new String[]{"Sheet1", "Sheet2", "Notes", "2026 Log", "'Odd Name'"}) {
            expect(post(root, "/sheets/v4/spreadsheets/SS1/values/"
                    + java.net.URLEncoder.encode(s, StandardCharsets.UTF_8) + ":clear", json("{}"), seen),
                    "DESTRUCTIVE", "clear an unbounded range (" + s + ")", 1);
        }
        // A reference with no sheet name is still a reference, and still judged on its ends.
        expect(post(root, "/sheets/v4/spreadsheets/SS1/values/A1:C10:clear", json("{}"), seen),
                "MUTATE", "clear A1:C10", 1);
        expect(post(root, "/sheets/v4/spreadsheets/SS1/values/A:Z:clear", json("{}"), seen),
                "DESTRUCTIVE", "clear an unbounded range (A:Z)", 1);
        expect(post(root, "/sheets/v4/spreadsheets/SS1/values/Sheet1!A:Z:clear", json("{}"), seen),
                "DESTRUCTIVE", "clear an unbounded range (Sheet1!A:Z)", 1);

        // Scale: a bulk write charges what it actually does.
        expect(post(root, "/sheets/v4/spreadsheets/SS1/values:batchUpdate",
                json("{\"data\":[" + ranges(500) + "],\"valueInputOption\":\"RAW\"}"), seen),
                "MUTATE", "write cells in 500 ranges", 500);

        expect(patch(root, "/drive/v3/files/FID", json("{\"trashed\":true}"), seen),
                "DESTRUCTIVE", "send to trash", 1);

        expect(patch(root, "/drive/v3/files/FID", json("{\"name\":\"renamed\"}"), seen),
                "MUTATE", "update", 1);

        expect(post(root, "/gmail/v1/users/me/messages/batchDelete",
                json("{\"ids\":[" + ids(340) + "]}"), seen),
                "DESTRUCTIVE", "permanently delete", 340);

        // Multipart and unzipped, which is how a real batch envelope arrives: BatchRequest builds it from
        // the plain request factory, so it is the one Google body that never gets gzipped.
        expect(batch(root, multipart("GET", "GET", "GET"), seen),
                "READ", "batch of 3 requests, all reads", 3);

        expect(batch(root, multipart("GET", "DELETE", "GET"), seen),
                "DESTRUCTIVE", "batch of 3 requests — worst is permanently delete", 3);

        // A body the wallet genuinely cannot read still takes the strict branch, and now says why.
        expect(postRaw(root, "/sheets/v4/spreadsheets/SS1:batchUpdate",
                "not json at all".getBytes(StandardCharsets.UTF_8), "application/json", null, seen),
                "DESTRUCTIVE", "batch update — the wallet could not read this request, so it counts"
                        + " as irreversible", 1);

        // A Drive upload is multipart too, and must stay a stream: buffering it would put every file
        // under the parse limit through the heap on the one path where speed is a design requirement.
        expect(postRaw(root, "/drive/v3/files?uploadType=multipart", multipart("POST"),
                "multipart/related; boundary=x", null, seen),
                "MUTATE", "create or upload", 1);
        check("an upload body is left as a stream", !buffered.get());

        expect(batch(root, multipart("GET"), seen), "READ", "batch of 1 requests, all reads", 1);
        check("a batch envelope is buffered", buffered.get());

        rewriteCheck();

        server.stop(0);
        System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void rewriteCheck() {
        var body = multipart("GET", "POST");
        var env = BatchEnvelope.of(body, "gmail");
        var out = new String(env.rewritten(), StandardCharsets.ISO_8859_1);
        check("rewrite strips the alias", !out.contains("/g/gmail/"));
        check("rewrite strips this machine off the line", !out.contains("127.0.0.1"));
        check("rewrite keeps the real path", out.contains("GET /gmail/v1/users/me/messages/m0"));
        check("rewrite keeps the query", out.contains("?format=metadata"));
        check("rewrite keeps the HTTP version", out.contains(" HTTP/1.1"));
        check("the classifier sees the upstream path, not the URL",
                env.subs().get(0).path().equals("/gmail/v1/users/me/messages/m0"));
        check("rewrite leaves the rest alone", out.contains("Content-ID: <item0>")
                && out.contains("--batch_boundary--"));
        check("sub-request count", env.subs().size() == 2);
    }

    private static Classification post(String root, String path, byte[] body,
                                       AtomicReference<Classification> seen) throws Exception {
        return postRaw(root, path, gzip(body), "application/json; charset=UTF-8", "gzip", seen);
    }

    private static Classification get(String root, String path, String query,
                                      AtomicReference<Classification> seen) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(root + path + "?" + query)).GET();
        HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.discarding());
        return seen.get();
    }

    private static Classification batch(String root, byte[] body,
                                        AtomicReference<Classification> seen) throws Exception {
        return postRaw(root, "/gmail/batch", body, "multipart/mixed; boundary=batch_boundary", null, seen);
    }

    private static Classification patch(String root, String path, byte[] body,
                                        AtomicReference<Classification> seen) throws Exception {
        return send(root, path, "PATCH", gzip(body), "application/json; charset=UTF-8", "gzip", seen);
    }

    private static Classification postRaw(String root, String path, byte[] body, String type,
                                          String encoding, AtomicReference<Classification> seen)
            throws Exception {
        return send(root, path, "POST", body, type, encoding, seen);
    }

    private static Classification send(String root, String path, String method, byte[] body, String type,
                                       String encoding, AtomicReference<Classification> seen)
            throws Exception {
        var b = HttpRequest.newBuilder(URI.create(root + path)).header("Content-Type", type);
        if (encoding != null) b.header("Content-Encoding", encoding);
        // ofInputStream, not ofByteArray: it sends chunked with no Content-Length, which is exactly what
        // the Google client does once it gzips, and is the case the old guard failed to cover.
        b.method(method, HttpRequest.BodyPublishers.ofInputStream(
                () -> new java.io.ByteArrayInputStream(body)));
        HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.discarding());
        return seen.get();
    }

    private static void expect(Classification c, String tier, String operation, int count) {
        var ok = c != null && c.tier().name().equals(tier) && c.operation().equals(operation)
                && c.itemCount() == count;
        check(tier + " / " + operation + " / n=" + count, ok);
        if (!ok && c != null) {
            System.out.println("      got: " + c.tier() + " / " + c.operation() + " / n=" + c.itemCount());
        }
    }

    private static void check(String what, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Classification put(String root, String path, String query, byte[] body,
                                      AtomicReference<Classification> seen) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(root + path + "?" + query))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Content-Encoding", "gzip");
        b.method("PUT", HttpRequest.BodyPublishers.ofInputStream(
                () -> new java.io.ByteArrayInputStream(gzipQuietly(body))));
        HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.discarding());
        return seen.get();
    }

    private static byte[] gzipQuietly(byte[] raw) {
        try {
            return gzip(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String ranges(int n) {
        var sb = new StringBuilder();
        for (var i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"range\":\"Sheet1!A").append(i + 1)
              .append(":C").append(i + 1).append("\",\"values\":[[\"x\"]]}");
        }
        return sb.toString();
    }

    private static String ids(int n) {
        var sb = new StringBuilder();
        for (var i = 0; i < n; i++) sb.append(i == 0 ? "" : ",").append('"').append(i).append('"');
        return sb.toString();
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    /**
     * The shape a Google BatchRequest actually writes, measured rather than assumed: the sub-request
     * target is an ABSOLUTE URL carrying this machine's loopback host and the wallet's own alias.
     *
     * <p>The first version of this fixture used a bare path, because that is what 01-prp.04 quotes — but
     * that quote is Google's error message echoing the path it parsed, not the bytes we sent. The fixture
     * passed and the code under it did nothing.
     */
    private static byte[] multipart(String... methods) {
        var sb = new StringBuilder();
        for (var i = 0; i < methods.length; i++) {
            var target = "http://127.0.0.1:51751/g/gmail/gmail/v1/users/me/messages/m" + i
                    + (methods[i].equals("DELETE") ? "" : "?format=metadata");
            sb.append("--batch_boundary\r\n")
              .append("Content-Type: application/http\r\n")
              .append("Content-Transfer-Encoding: binary\r\n")
              .append("Content-ID: <item").append(i).append(">\r\n")
              .append("\r\n")
              .append(methods[i]).append(' ').append(target).append(" HTTP/1.1\r\n")
              .append("Accept-Encoding: gzip\r\n")
              .append("\r\n");
        }
        sb.append("--batch_boundary--\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private ClassifyProbe() {
    }
}

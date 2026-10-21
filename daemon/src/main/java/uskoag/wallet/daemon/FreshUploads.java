package uskoag.wallet.daemon;

import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Images a tool uploaded through this wallet moments ago, so that the two steps which always follow
 * are not mistaken for irreversible acts.
 *
 * <p>Docs and Slides insert a picture only from a public URL. A tool therefore uploads the local
 * image, shares it "anyone with the link, reader" for the moment of the insert, and trashes it after,
 * because the document keeps its own copy. The link-share and the trash are what Drive calls sharing
 * and deleting, and DriveRules correctly puts both in the destructive tier. Here they expose nothing
 * but the picture the same account uploaded seconds earlier, so the passphrase question there is
 * noise. Asking it anyway trains a person to type the passphrase without reading, which is the
 * failure the destructive tier exists to prevent.
 *
 * <p>The exemption is deliberately narrow, so it cannot be used to publish a document. It needs all
 * of these:
 * <ul>
 *   <li>The file was created by an upload through this wallet.</li>
 *   <li>The upload's media part starts with a PNG, JPEG, GIF or WEBP file signature. That is checked
 *       on the first 64 KB as they stream past, and put back; nothing is kept.</li>
 *   <li>It was uploaded by the same account.</li>
 *   <li>It is within {@link #WINDOW_MS}.</li>
 *   <li>The share is exactly type=anyone and role=reader.</li>
 * </ul>
 * Memory only: a restart forgets, and the cost of forgetting is one ordinary passphrase question.
 */
public final class FreshUploads {

    static final long WINDOW_MS = 15 * 60_000;

    private record Entry(String account, long at) {}

    private static final Map<String, Entry> FILES = new ConcurrentHashMap<>();

    private FreshUploads() {
    }

    /**
     * The request that carries a new file's bytes: a multipart POST (metadata and media together), or
     * the PUT of a resumable upload session (upload_id in the query, media only). The Java client uses
     * the resumable form for every upload, so the PUT is the common case; the id comes back on it.
     */
    static boolean isDriveUpload(RequestFacts f) {
        if (!f.api().equals("drive") || !f.path().startsWith("/upload/") || !f.path().endsWith("/files")) return false;
        return (f.is("POST") && f.query() != null && f.query().contains("uploadType=multipart")) || isResumablePut(f);
    }

    /** The bytes of a resumable upload: the body is the raw media, so its signature is at offset 0. */
    static boolean isResumablePut(RequestFacts f) { return f.is("PUT") && f.queryHas("upload_id"); }

    /** Record the file id from a successful Drive upload response, when the peek saw an image. */
    static void noteUpload(RequestFacts f, String account, boolean image, int status, byte[] response) {
        if (!image || status / 100 != 2 || response == null || !isDriveUpload(f)) return;
        try {
            var id = JsonParser.parseString(new String(response, StandardCharsets.UTF_8)).getAsJsonObject().get("id");
            if (id != null) FILES.put(id.getAsString(), new Entry(account, System.currentTimeMillis()));
        } catch (Exception ignored) {
            // not JSON, or no id: nothing to remember
        }
    }

    /** A narrower classification for a share or trash of a fresh image upload, else the one given. */
    static Classification relax(Classification c, RequestFacts f, String account) {
        if (!f.api().equals("drive") || c.tier() != uskoag.wallet.wire.Tier.DESTRUCTIVE) return c;
        var id = c.resource().id();
        var e = id == null ? null : FILES.get(id);
        if (e == null || !e.account().equals(account) || System.currentTimeMillis() - e.at() > WINDOW_MS) return c;
        if (f.is("POST") && f.path().endsWith("/files/" + id + "/permissions") && anyoneReader(f.body()))
            return Classification.mutate("link-share an image it just uploaded (for inserting into a document)", c.resource());
        if (f.is("PATCH") && f.path().endsWith("/files/" + id) && BatchRequests.bodySays(f.body(), "trashed", true))
            return Classification.mutate("trash an image it just uploaded", c.resource());
        return c;
    }

    private static boolean anyoneReader(byte[] body) {
        if (body == null) return false;
        try {
            var o = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            return o.size() == 2 && "anyone".equals(o.get("type").getAsString()) && "reader".equals(o.get("role").getAsString());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * For a Drive upload only: the first bytes, taken off the stream so the relay can put them back in
     * front of the rest (the same mechanism as an over-limit body), and whether the media part in them
     * STARTS with an image file signature. Signatures, not the declared type: a type is whatever the
     * client wrote, and a PDF labelled image/png is still a PDF.
     */
    static final int PEEK = 64 * 1024;

    record Peek(byte[] prefix, boolean image) {}

    static Peek peek(java.io.InputStream in, String contentEncoding, boolean rawMedia) throws java.io.IOException {
        var prefix = in.readNBytes(PEEK);
        var plain = prefix;
        if (contentEncoding != null && contentEncoding.toLowerCase().contains("gzip")) {
            var out = new java.io.ByteArrayOutputStream();
            try (var gz = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(prefix))) {
                var buf = new byte[8192];
                for (var n = gz.read(buf); n > 0 && out.size() < PEEK; n = gz.read(buf)) out.write(buf, 0, n);
            } catch (java.io.IOException truncatedIsExpected) {
                // the prefix is a cut-off gzip stream; what decoded so far is all that is needed
            }
            plain = out.toByteArray();
        }
        return new Peek(prefix, rawMedia ? signatureAt(plain, 0) : mediaIsImage(plain));
    }

    /** multipart/related: part 1 is the JSON metadata, part 2 the media. Find part 2's first byte. */
    static boolean mediaIsImage(byte[] b) {
        var s = new String(b, StandardCharsets.ISO_8859_1);
        var first = s.indexOf("\r\n\r\n");
        if (first < 0) return false;
        var boundaryAt = s.indexOf("\r\n--", first);
        if (boundaryAt < 0) return false;
        var mediaHeadEnd = s.indexOf("\r\n\r\n", boundaryAt + 4);
        if (mediaHeadEnd < 0) return false;
        return signatureAt(b, mediaHeadEnd + 4);
    }

    static boolean signatureAt(byte[] b, int at) {
        if (b.length < at + 12) return false;
        return starts(b, at, 0x89, 'P', 'N', 'G') || starts(b, at, 0xFF, 0xD8, 0xFF) || starts(b, at, 'G', 'I', 'F', '8')
                || (starts(b, at, 'R', 'I', 'F', 'F') && starts(b, at + 8, 'W', 'E', 'B', 'P'));
    }

    private static boolean starts(byte[] b, int at, int... sig) {
        for (var i = 0; i < sig.length; i++) if ((b[at + i] & 0xFF) != sig[i]) return false;
        return true;
    }
}

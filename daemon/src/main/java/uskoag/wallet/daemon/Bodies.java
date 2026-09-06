package uskoag.wallet.daemon;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * The request body in the two forms the wallet needs it: exactly as it arrived, and readable.
 *
 * <p>They are not the same bytes, and for six sessions nobody noticed. Every Google Java client gzips
 * its request bodies — {@code AbstractGoogleClientRequest} calls {@code setEncoding(new GZipEncoding())}
 * unless a caller disables it, and nothing in gservices does — so what reaches the proxy begins
 * {@code 1f 8b 08} and no JSON parser will ever read it. {@link BatchRequests} was handed those bytes on
 * every call it has ever received and returned an empty list every time.
 *
 * <p>That single fact pointed both ways. Adding a tab to a spreadsheet reached the "body unreadable"
 * branch and was classified irreversible, so it demanded the passphrase; while {@code DriveRules} decides
 * "send to trash" by looking for {@code trashed:true} in the body, never found it, and classified every
 * trashing as an ordinary reversible update. The tier exists to catch that operation and had never once
 * caught it.
 *
 * @param buffered the complete body when it fitted, forwarded byte-identical — the upstream leg must
 *                 receive what the client sent, never a re-encoded copy
 * @param prefix   what had already been consumed from the stream when the body turned out to be too
 *                 large, so the relay can put it back in front of the rest. This is not hypothetical
 *                 tidiness: the previous code read {@code PARSE_LIMIT} bytes and then forwarded that
 *                 truncated array, and since a gzipped body is chunked and carries no Content-Length,
 *                 nothing stopped it — a large batch was being silently cut in half on its way to Google
 * @param readable the same body decompressed, for classification only. Null when it could not be read,
 *                 which routes to the stricter branch by design
 */
public record Bodies(byte[] buffered, byte[] prefix, byte[] readable) {

    public static final Bodies NONE = new Bodies(null, null, null);

    /**
     * Buffers a body only when it is a command rather than content: JSON, or a multipart batch envelope
     * whose sub-request lines have to be classified and rewritten. Media is never parsed, which is what
     * keeps large uploads fast and keeps document contents out of the policy layer entirely.
     */
    public static Bodies of(HttpExchange x, int limit, boolean batchEnvelope) throws IOException {
        var type = header(x, "Content-Type");
        if (type == null) return NONE;
        var t = type.toLowerCase(Locale.ROOT);
        // Multipart is buffered ONLY for a batch envelope, and the caller decides that from the path.
        // A Drive upload is multipart/related — buffering those would put every uploaded file under a
        // megabyte through the heap for no purpose, on the one path where speed was a design requirement.
        if (!t.contains("json") && !(batchEnvelope && t.contains("multipart/"))) return NONE;

        var declared = header(x, "Content-Length");
        if (declared != null && Long.parseLong(declared) > limit) return NONE;

        // NOT try-with-resources. When the body is over the limit the relay puts this prefix back in
        // front of the rest of THIS stream, so closing it here severed the remainder and every body
        // over the limit died upstream with "Stream is closed" — a 4MB attachment could never be sent.
        var in = x.getRequestBody();
        byte[] read;
        try {
            // One past the limit, so "exactly full" can be told from "did not fit" — readNBytes(limit)
            // cannot distinguish them, which is how the truncation above went unseen.
            read = in.readNBytes(limit + 1);
        } catch (IOException e) {
            in.close();
            throw e;
        }
        if (read.length > limit) return new Bodies(null, read, null);
        in.close();
        return new Bodies(read, null, decode(read, header(x, "Content-Encoding"), limit));
    }

    /**
     * Decompressed, or null.
     *
     * <p>Bounded on the way out as well as the way in, which is the only part of this worth thinking
     * about: a megabyte of gzip expands to a gigabyte if someone wants it to, and the wallet would
     * allocate it. Reading one byte past the limit and refusing costs nothing and closes that.
     */
    private static byte[] decode(byte[] raw, String encoding, int limit) {
        if (raw == null || raw.length == 0) return raw;
        if (encoding == null || encoding.isBlank()) return raw;
        var enc = encoding.toLowerCase(Locale.ROOT);
        if (!enc.contains("gzip")) {
            Log.warn("request body arrived with Content-Encoding: " + encoding
                    + " — the wallet cannot read it, so it will be classified strictly");
            return null;
        }
        try (var in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            var out = in.readNBytes(limit + 1);
            if (out.length > limit) {
                Log.warn("gzipped request body expands past " + limit
                        + " bytes; refusing to decompress it and classifying strictly");
                return null;
            }
            return out;
        } catch (IOException e) {
            Log.warn("could not decompress a gzipped request body: " + e);
            return null;
        }
    }

    private static String header(HttpExchange x, String name) {
        var v = x.getRequestHeaders().getFirst(name);
        return v == null || v.isBlank() ? null : v;
    }

    /** The same body with different bytes to forward, for the batch envelope rewrite. */
    public Bodies withBuffered(byte[] replacement) {
        return new Bodies(replacement, prefix, readable);
    }

    /**
     * True when what we forward and what we could read are the same object, so editing one edits the
     * other. False for a compressed body: there the readable form is a decompressed copy, and sending
     * that upstream under a {@code Content-Encoding: gzip} header would hand Google bytes it cannot read.
     */
    public boolean rewritable() {
        return buffered != null && buffered == readable;
    }
}

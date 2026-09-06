package uskoag.wallet.daemon;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A {@code multipart/mixed} HTTP batch, read only as far as each sub-request's request-line.
 *
 * <p>Two things need that line and nothing deeper. A batch of a hundred GETs is a read, and classifying
 * the envelope by its own POST verb — which is what happened until now — demanded write access for
 * traffic that only ever read, and recorded it in the audit as modification. And the request-lines still
 * carry the wallet's own {@code /g/<alias>} prefix upstream, because the Google client builds them from
 * the loopback root it was given, so Google resolves {@code /g/gmail/gmail/v1/...} against its own host
 * and rejects every item. That defect has been open since 2026-08-02 and is the reason every gmailcli
 * enumeration command was dead.
 *
 * <p>Sub-headers and sub-bodies are neither parsed nor touched. The rewrite replaces a prefix the wallet
 * invented, and nothing else, so the relayed envelope is otherwise byte-identical.
 *
 * <p>Text handling is ISO-8859-1 in both directions on purpose: it round-trips every byte sequence
 * unchanged, so a sub-body holding UTF-8 or raw bytes survives being read as characters and written back.
 */
public record BatchEnvelope(List<BatchSub> subs, byte[] rewritten) {

    private static final Pattern REQUEST_LINE =
            Pattern.compile("(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) (\\S+)( HTTP/\\d\\.\\d)?\\s*");

    /**
     * Each part declares the length of the sub-request it carries, and shortening a request line changes
     * it. The real client emits {@code Content-Length: 157} per part; taking
     * {@code http://127.0.0.1:61347/g/gmail} out of the line makes it 127, and leaving the header saying
     * 157 hands Google a part whose header contradicts its body. Whether a given parser trusts the
     * boundary or the count is not something to find out in production, so the count is corrected.
     *
     * <p>Only visible against bytes a real client produced — the fixture this was first written against
     * had no such header, and every check passed.
     */
    private static final Pattern CONTENT_LENGTH =
            Pattern.compile("(?i)(content-length:\\s*)(\\d+)(\\s*)");

    /** The proxy path of a batch envelope, once the {@code /g/<alias>/} prefix is already off. */
    public static boolean isEnvelope(String path) {
        var p = path.startsWith("/") ? path.substring(1) : path;
        return p.equals("batch") || p.startsWith("batch/");
    }

    public static BatchEnvelope of(byte[] body, String alias) {
        var subs = new ArrayList<BatchSub>();
        if (body == null || body.length == 0) return new BatchEnvelope(subs, body);

        var text = new String(body, StandardCharsets.ISO_8859_1);
        var out = new StringBuilder(text.length());
        var prefix = "/g/" + alias;
        var part = new ArrayList<String>();
        var lengthAt = -1;
        var delta = 0;
        var expecting = false;
        var changed = false;

        for (var at = 0; at < text.length(); ) {
            var end = text.indexOf('\n', at);
            if (end < 0) end = text.length() - 1;
            var line = text.substring(at, end + 1);
            var bare = line.strip();

            if (bare.startsWith("--")) {
                // A boundary closes the part before it, which is the only moment its declared length can
                // be corrected: the header sits above the body it describes.
                flush(out, part, lengthAt, delta);
                part.clear();
                lengthAt = -1;
                delta = 0;
                expecting = false;
                out.append(line);
            } else if (bare.isEmpty()) {
                // The blank line closing a part's headers; the next non-empty line is the request-line.
                expecting = true;
                part.add(line);
            } else if (expecting) {
                expecting = false;
                var m = REQUEST_LINE.matcher(bare);
                var stripped = m.matches() ? strip(m.group(2), prefix) : null;
                if (stripped == null) {
                    part.add(line);
                } else {
                    subs.add(sub(m.group(1), stripped));
                    if (stripped.equals(m.group(2))) {
                        part.add(line);
                    } else {
                        changed = true;
                        delta += stripped.length() - m.group(2).length();
                        part.add(line.replace(m.group(2), stripped));
                    }
                }
            } else {
                if (lengthAt < 0 && CONTENT_LENGTH.matcher(bare).matches()) lengthAt = part.size();
                part.add(line);
            }
            at = end + 1;
        }
        flush(out, part, lengthAt, delta);
        return new BatchEnvelope(subs, changed ? out.toString().getBytes(StandardCharsets.ISO_8859_1) : body);
    }

    /** Writes one part out, with its declared length reconciled to what the part now actually carries. */
    private static void flush(StringBuilder out, List<String> part, int lengthAt, int delta) {
        if (lengthAt >= 0 && delta != 0) {
            var m = CONTENT_LENGTH.matcher(part.get(lengthAt));
            if (m.matches()) {
                var fixed = Math.max(0, Integer.parseInt(m.group(2)) + delta);
                part.set(lengthAt, m.group(1) + fixed + m.group(3));
            }
        }
        for (var l : part) out.append(l);
    }

    /**
     * The upstream path, out of whatever the client wrote as the sub-request target.
     *
     * <p><b>It writes an absolute URL, not a path.</b> {@code HttpRequestContent} emits
     * {@code request.getUrl().build()}, so the line reads {@code GET http://127.0.0.1:51751/g/gmail/gmail/v1/...}
     * — scheme, host, our loopback port and all. Measured against the real client; the handover note in
     * {@code 01-prp.04} quotes Google's error echoing back {@code /g/gmail/gmail/v1/...}, which is Google
     * reporting the path it parsed out, and reading that as the wire format is how the first version of
     * this only handled a leading slash and would have stripped nothing at all.
     *
     * <p>So: drop the scheme and authority if present, then drop the wallet's own alias. What is left is
     * what Google's own batch documentation asks for — a path — and it no longer names this machine.
     */
    private static String strip(String target, String prefix) {
        var path = target;
        var scheme = path.indexOf("://");
        if (scheme > 0) {
            var slash = path.indexOf('/', scheme + 3);
            path = slash < 0 ? "/" : path.substring(slash);
        }
        if (path.equals(prefix)) return "/";
        return path.startsWith(prefix + "/") ? path.substring(prefix.length()) : path;
    }

    private static BatchSub sub(String method, String target) {
        var q = target.indexOf('?');
        return q < 0 ? new BatchSub(method, target, null)
                : new BatchSub(method, target.substring(0, q), target.substring(q + 1));
    }
}

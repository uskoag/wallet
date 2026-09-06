package uskoag.wallet.daemon;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The rewrite run over bytes a real Google client actually produced, rather than over a fixture written
 * from a description of them.
 *
 * <p>This exists because the fixture was wrong. It used a bare path for each sub-request target, taken
 * from the quoted error in {@code 01-prp.04} — which is Google echoing back the path it parsed, not the
 * line we sent. The real client writes an absolute URL carrying this machine's loopback host. Every check
 * passed against the fixture while the code under it stripped nothing.
 *
 * <p>Takes the envelope captured by {@code BatchEnvProbe} (100 queued Gmail GETs, ~32 KB) as its argument.
 */
public final class RealEnvelopeProbe {

    private static int failures;

    public static void main(String[] args) throws Exception {
        var file = Path.of(args.length > 0 ? args[0] : "real-envelope.bin");
        if (!Files.exists(file)) {
            System.out.println("no captured envelope at " + file.toAbsolutePath());
            System.exit(2);
        }
        var body = Files.readAllBytes(file);
        System.out.println("captured envelope: " + body.length + " bytes");

        var env = BatchEnvelope.of(body, "gmail");
        var out = new String(env.rewritten(), StandardCharsets.ISO_8859_1);
        var in = new String(body, StandardCharsets.ISO_8859_1);

        check("every sub-request was found", env.subs().size() == 100);
        check("no wallet alias survives", !out.contains("/g/gmail"));
        check("this machine is not named upstream", !out.contains("127.0.0.1"));
        check("the paths are upstream paths",
                env.subs().stream().allMatch(s -> s.path().startsWith("/gmail/v1/users/me/messages/")));
        check("the queries survive",
                env.subs().stream().allMatch(s -> "format=metadata".equals(s.query())));
        check("every sub-request is a GET",
                env.subs().stream().allMatch(s -> "GET".equals(s.method())));
        check("it classifies as a read",
                BatchRequests.rankEnvelope(new RequestFacts("gmail", "POST", "/batch", null, body), env)
                        .tier() == uskoag.wallet.wire.Tier.READ);
        check("the boundary is untouched", sameCount(in, out, "--__END_OF_PART__"));
        check("the part headers survive", sameCount(in.toLowerCase(), out.toLowerCase(), "content-id:"));
        check("the envelope got shorter", out.length() < in.length());
        check("each part's declared length matches what it now carries", lengthsAgree(out));

        System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * Every part says how long its sub-request is. Shortening a request line without correcting that
     * number hands Google a part whose header contradicts its body, and which of the two a parser
     * believes is not something to discover in production.
     */
    private static boolean lengthsAgree(String envelope) {
        var delim = envelope.substring(0, envelope.indexOf('\r'));
        var checked = 0;
        for (var at = envelope.indexOf(delim); at >= 0; ) {
            var next = envelope.indexOf(delim, at + delim.length());
            if (next < 0) break;
            var headerEnd = envelope.indexOf("\r\n\r\n", at);
            if (headerEnd < 0 || headerEnd > next) break;
            var declared = declaredLength(envelope.substring(at, headerEnd));
            // The payload runs to the CRLF that precedes the next boundary.
            var actual = next - (headerEnd + 4) - 2;
            if (declared >= 0) {
                if (declared != actual) {
                    System.out.println("      part at " + at + ": declared " + declared
                            + ", actually " + actual);
                    return false;
                }
                checked++;
            }
            at = next;
        }
        System.out.println("      (" + checked + " parts checked)");
        return checked > 0;
    }

    private static int declaredLength(String headers) {
        for (var line : headers.split("\r\n")) {
            var l = line.trim().toLowerCase();
            if (l.startsWith("content-length:")) {
                return Integer.parseInt(l.substring("content-length:".length()).trim());
            }
        }
        return -1;
    }

    private static boolean sameCount(String a, String b, String needle) {
        return count(a, needle) == count(b, needle) && count(a, needle) > 0;
    }

    private static int count(String s, String needle) {
        var n = 0;
        for (var at = s.indexOf(needle); at >= 0; at = s.indexOf(needle, at + 1)) n++;
        return n;
    }

    private static void check(String what, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }

    private RealEnvelopeProbe() {
    }
}

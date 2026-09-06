package uskoag.wallet.daemon;

import uskoag.wallet.wire.GApi;

/**
 * Google hands back absolute URLs in a few places, and every one of them is a route around the proxy.
 *
 * <p>The one that matters is the resumable upload: the initiation response's {@code Location} points
 * straight at googleapis.com, and the client library will happily PUT the rest of the file there —
 * carrying a wallet handle Google has never heard of, so the upload fails. Rewriting it back keeps
 * large uploads working and keeps them inside the policy at the same time.
 */
public final class Rewrite {

    private Rewrite() {
    }

    /**
     * The outbound counterpart, for a multipart batch envelope.
     *
     * <p>Each sub-request's request-line is built by the Google client from the loopback root it was
     * given, so it carries the wallet's own {@code /g/<alias>} prefix into a body Google resolves against
     * its own host — and every item comes back "could not be resolved". The alias is the wallet's
     * invention, so taking it back out is the wallet's job.
     */
    public static byte[] outboundBatch(byte[] body, GApi api) {
        return BatchEnvelope.of(body, api.alias).rewritten();
    }

    /** An upstream absolute URL turned back into a loopback one, or the value untouched. */
    public static String inbound(String value, int proxyPort) {
        if (value == null || !value.startsWith("http")) return value;
        for (var api : GApi.values()) {
            if (value.startsWith(api.upstream)) {
                return "http://127.0.0.1:" + proxyPort + "/g/" + api.alias + "/"
                        + value.substring(api.upstream.length());
            }
        }
        return value;
    }
}

package uskoag.wallet.daemon;

import java.util.Set;

/**
 * Which headers must not be copied across the hop.
 *
 * <p>Two separate reasons, worth keeping straight. The hop-by-hop set is an HTTP rule: they describe
 * one connection, not the message. The restricted set is a {@code java.net.http} rule: setting any of
 * them on a request throws, because the client owns them.
 */
public final class Hop {

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade");

    private static final Set<String> CLIENT_OWNED = Set.of(
            "content-length", "date", "expect", "from", "host", "via", "warning");

    private static final Set<String> OURS = Set.of("authorization", "x-wallet-grant");

    private Hop() {
    }

    public static boolean skipOutbound(String name) {
        var n = name.toLowerCase();
        return HOP_BY_HOP.contains(n) || CLIENT_OWNED.contains(n) || OURS.contains(n);
    }

    public static boolean skipInbound(String name) {
        var n = name.toLowerCase();
        return HOP_BY_HOP.contains(n) || "content-length".equals(n);
    }
}

package uskoag.wallet.daemon;

import uskoag.wallet.wire.CorrelationCode;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live handles, in memory only.
 *
 * <p>Locking the wallet empties this map, which is why revocation here is instant and total: everything
 * in flight stops rather than leaked tokens living out their hour.
 */
public final class Grants {

    private static final SecureRandom RNG = new SecureRandom();

    private final Map<String, Grant> live = new ConcurrentHashMap<>();

    public Grant issue(String account, String profile, String appName, String api,
                       String session, long pid, String peerCommand) {
        var raw = new byte[24];
        RNG.nextBytes(raw);
        var g = new Grant(HexFormat.of().formatHex(raw), account, profile, appName, api, session,
                CorrelationCode.next(), pid, peerCommand, System.currentTimeMillis());
        live.put(g.token(), g);
        return g;
    }

    public Grant get(String token) {
        return token == null ? null : live.get(token);
    }

    public void clear() {
        live.clear();
    }

    public int size() {
        return live.size();
    }
}

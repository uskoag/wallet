package uskoag.wallet.daemon;

import uskoag.wallet.wire.Json;
import uskoag.wallet.wire.WalletPaths;

import java.nio.file.Files;

/**
 * Two timestamps in the clear: when the daily credential check last finished, and when it last asked for
 * the passphrase.
 *
 * <p>Outside the keyring, and that is the design rather than an oversight. Its only job is to decide
 * whether to <em>ask</em> for the passphrase — a decision that has to be makeable while locked, which
 * rules out storing it anywhere that needs the passphrase to read. It is also not a control: the worst
 * anything running as this user can do by editing it is suppress a maintenance prompt or provoke a spare
 * one. Every authoritative result — what was found, when each credential was last healthy, when it went
 * stale — stays sealed inside the keyring with the tokens.
 *
 * <p>Read and written by value rather than held open, because the wallet is not the only thing that may
 * touch this file and a stale in-memory copy is how a check comes to run twice.
 */
public final class HealthMarker {

    long lastSweepAt, lastPromptAt;

    public static HealthMarker load() {
        try {
            var f = WalletPaths.healthFile();
            if (!Files.exists(f)) return new HealthMarker();
            var m = Json.to(Files.readString(f), HealthMarker.class);
            return m == null ? new HealthMarker() : m;
        } catch (Exception e) {
            Log.warn("could not read " + WalletPaths.healthFile() + " — treating the daily check as due: " + e);
            return new HealthMarker();
        }
    }

    public void save() {
        try {
            Files.createDirectories(WalletPaths.home());
            Files.writeString(WalletPaths.healthFile(), Json.of(this));
        } catch (Exception e) {
            // Not fatal, and deliberately not escalated: losing this costs one extra prompt, and a
            // maintenance job that refuses to run because it could not write its own bookmark would be
            // worse than the thing it is trying to prevent.
            Log.warn("could not write " + WalletPaths.healthFile() + ": " + e);
        }
    }

    boolean sweptWithin(long millis) {
        return lastSweepAt > 0 && System.currentTimeMillis() - lastSweepAt < millis;
    }

    boolean askedWithin(long millis) {
        return lastPromptAt > 0 && System.currentTimeMillis() - lastPromptAt < millis;
    }
}

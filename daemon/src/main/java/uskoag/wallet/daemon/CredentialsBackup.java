package uskoag.wallet.daemon;

import uskoag.wallet.wire.WalletPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Keeps a plain copy of every {@code credentials.json} the wallet is given, outside the keyring.
 *
 * <p>This exists so that losing the passphrase costs the refresh tokens and nothing more. Without it a
 * reset also means re-downloading a client secret from the Cloud console for every account across every
 * org, which is the sort of pain that makes people never reset — and a wallet you are afraid to reset
 * is worse than one you are not.
 *
 * <p>The cost is real and stated at {@link WalletPaths#credentialsBackup()}: the client secret sits on
 * disk in the clear. Turn it off with {@code backupCredentialsJson = false} once the wallet is settled,
 * and run {@code purgebackups} to remove what is already there.
 */
public final class CredentialsBackup {

    private CredentialsBackup() {
    }

    /** One file per org, because that is what an OAuth client belongs to. */
    public static void save(String orgId, String json) {
        try {
            var dir = WalletPaths.credentialsBackup();
            Files.createDirectories(dir);
            var file = dir.resolve(safe(orgId) + ".credentials.json");
            Files.writeString(file, json);
            Restrict.toOwner(file);
        } catch (Exception e) {
            Log.warn("could not back up credentials.json for org " + orgId + " - " + e);
        }
    }

    /** Every backed-up pair, as {@code account/profile} plus the file. */
    public static List<Path> all() {
        var out = new ArrayList<Path>();
        var root = WalletPaths.credentialsBackup();
        if (!Files.isDirectory(root)) return out;
        try (var walk = Files.walk(root)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".credentials.json")).forEach(out::add);
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    public static String orgOf(Path file) {
        var name = file.getFileName().toString();
        return name.substring(0, name.length() - ".credentials.json".length());
    }

    /**
     * Re-populates a fresh keyring's orgs from the backups. The accounts still need consent afterwards
     * — a refresh token is the one thing this cannot bring back, and should not be able to.
     */
    public static int restoreInto(WalletCore core) throws IOException {
        var restored = 0;
        for (var file : all()) {
            try {
                var json = Files.readString(file);
                var secrets = ClientJson.parse(json);
                var id = orgOf(file);
                var org = core.keyring.org(id).orElseGet(() -> {
                    var fresh = new OrgRecord(id);
                    core.keyring.data().orgs().add(fresh);
                    return fresh;
                });
                org.label = id;
                org.credentialsJson = json;
                org.clientId = secrets.get("client_id");
                org.clientSecret = secrets.get("client_secret");
                restored++;
            } catch (Exception e) {
                Log.warn("could not restore " + file + " - " + e);
            }
        }
        if (restored > 0) core.keyring.save();
        return restored;
    }

    /** Drops one org's backup, so a rename does not leave the old id lying around to be restored later. */
    public static void forget(String orgId) {
        try {
            Files.deleteIfExists(WalletPaths.credentialsBackup().resolve(safe(orgId) + ".credentials.json"));
        } catch (Exception e) {
            Log.warn("could not drop the old backup for " + orgId + " - " + e);
        }
    }

    public static int purge() {
        var gone = 0;
        for (var file : all()) {
            try {
                Files.delete(file);
                gone++;
            } catch (IOException ignored) {
            }
        }
        return gone;
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9._@-]", "_");
    }
}

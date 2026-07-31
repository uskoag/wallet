package uskoag.wallet.daemon;

import com.google.api.client.http.javanet.NetHttpTransport;
import uskoag.gservices.OAuthToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Migration, which replaces rotation and is better.
 *
 * <p>Hand it an old app-key once: it decrypts the existing {@code tokens_<md5>} stores, re-encrypts
 * what it finds into the keyring, and can then delete the old directories. No account re-consents,
 * across any number of orgs.
 *
 * <p>The useful consequence is that once those stores are gone, an app-key that has leaked into a
 * transcript stops being a secret — a passphrase that decrypts nothing is not a passphrase. That is
 * cleaner than rotating, which would mean re-consenting every account everywhere.
 *
 * <p>Note what a single account looks like on disk today: its tokens are scattered across one directory
 * per tool, each holding a token with that tool's scopes. So import walks every profile's root and
 * merges them into one record per email, which is also how the scope union gets seeded.
 */
public final class ImportOldStore {

    private ImportOldStore() {
    }

    /** Every account directory under this root that carries a credentials.json. */
    public static List<String> accounts(Path root) {
        var out = new ArrayList<String>();
        if (!Files.isDirectory(root)) return out;
        try (var dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve("credentials.json")))
                    .forEach(d -> out.add(d.getFileName().toString()));
        } catch (IOException ignored) {
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    public static String credentialsJson(Path root, String account) throws IOException {
        return Files.readString(root.resolve(account).resolve("credentials.json"));
    }

    /**
     * Reads one account's stored token for one tool's scope set. Null when that account was never
     * logged in with this app-key under this tool, which is an ordinary outcome and not an error.
     */
    public static Found read(Path root, String account, String appKey, List<String> scopes) {
        try {
            var dir = root.resolve(account);
            if (!Files.exists(dir.resolve("credentials.json"))) return null;

            var rest = scopes.subList(1, scopes.size()).toArray(String[]::new);
            var stored = OAuthToken.oauthToken("uskoag-wallet-import", appKey, scopes.getFirst(), rest)
                    .allCredsDir(root)
                    .credential(account)
                    .loadStoredCredential(new NetHttpTransport());

            if (stored == null || stored.getRefreshToken() == null) return null;
            return new Found(stored.getRefreshToken(), scopes, Files.readString(dir.resolve("credentials.json")));
        } catch (Exception e) {
            return null;
        }
    }
}

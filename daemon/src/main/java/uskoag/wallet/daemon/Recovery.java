package uskoag.wallet.daemon;

import uskoag.wallet.wire.WalletPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * What to do when the passphrase is gone.
 *
 * <p>There is no back door, and there should not be: a keyring you can open without the passphrase is
 * not a keyring. So recovery means starting a new one — and the whole job here is making that cost as
 * little as possible.
 *
 * <p>What is lost: the refresh tokens and the standing permissions, so every account consents once more
 * and the approved list is rebuilt as you work. The audit's detail columns also become unreadable,
 * since their key lived inside the keyring.
 *
 * <p>What is kept: every {@code credentials.json}, from the backup beside the keyring, so nothing has
 * to be fetched from the Cloud console again. And the old keyring is moved aside rather than deleted —
 * if the passphrase is remembered next week, it is still there.
 */
public final class Recovery {

    private Recovery() {
    }

    public static String reset(WalletCore core) throws IOException {
        core.lock();
        var stamp = System.currentTimeMillis();
        var kept = "no previous keyring";
        if (Files.exists(WalletPaths.keyringFile())) {
            var aside = WalletPaths.orphanedKeyring(stamp);
            Files.move(WalletPaths.keyringFile(), aside, StandardCopyOption.REPLACE_EXISTING);
            kept = "previous keyring moved to " + aside.getFileName();
        }
        // The audit's column key died with the old keyring, so its detail columns are now noise.
        deleteTree(WalletPaths.auditRoot());
        Log.warn("KEYRING RESET — " + kept);
        return kept;
    }

    /** Backed-up credentials.json files waiting to be pulled into a freshly created keyring. */
    public static int pending() {
        return CredentialsBackup.all().size();
    }

    private static void deleteTree(java.nio.file.Path root) {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            Log.warn("could not clear the audit store — " + e);
        }
    }
}

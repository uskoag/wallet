package uskoag.wallet.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A reminder the owner wrote for themself, shown on the unlock window: where the passphrase is kept, for
 * example the name of its entry in a password manager. Never the passphrase. Plain text beside the
 * keyring, because it has to be readable while the wallet is locked; empty means none.
 */
final class PassphraseHint {

    private PassphraseHint() {
    }

    static Path file() {
        return uskoag.wallet.wire.WalletPaths.home().resolve("passphrase-hint.txt");
    }

    static String read() {
        try {
            return Files.exists(file()) ? Files.readString(file(), StandardCharsets.UTF_8).strip() : "";
        } catch (Exception e) {
            return "";
        }
    }

    static void write(String hint) {
        try {
            var text = hint == null ? "" : hint.strip();
            if (text.isEmpty()) Files.deleteIfExists(file());
            else Files.writeString(file(), text, StandardCharsets.UTF_8);
        } catch (Exception e) {
            uskoag.wallet.daemon.Log.error("could not save the passphrase hint", e);
        }
    }
}

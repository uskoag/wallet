package uskoag.wallet.wire;

import java.nio.file.Path;

/**
 * The one place that knows where the wallet keeps things, shared by daemon, UI and CLI so they cannot
 * disagree about where the handshake lives.
 */
public final class WalletPaths {

    private WalletPaths() {
    }

    /** {@code %USERPROFILE%/uskoag/wallet} — overridable so a sandbox gets a whole separate keyring. */
    public static Path home() {
        var override = System.getenv("UKAG_WALLET_HOME");
        return override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), "uskoag", "wallet");
    }

    /** Machine-written handshake: control port, proxy port, token. */
    public static Path handshakeFile() {
        return home().resolve("wallet.json");
    }

    /** Human-edited settings. */
    public static Path configFile() {
        return home().resolve("wallet.toml");
    }

    /**
     * The keyring: refresh tokens, client secrets and {@code credentials.json} contents, AES-GCM
     * encrypted under the master key and then DPAPI-wrapped with the passphrase as optional entropy.
     */
    public static Path keyringFile() {
        return home().resolve("keyring.bin");
    }

    /** Deliberately separate from the keyring: a store you open routinely is the wrong home for secrets. */
    public static Path auditRoot() {
        return home().resolve("audit");
    }

    public static Path logFile() {
        return home().resolve("wallet.log");
    }

    /**
     * Plain copies of each account's {@code credentials.json}, so a forgotten passphrase costs the
     * refresh tokens and nothing else.
     *
     * <p>Say the trade honestly: this file holds the OAuth client_id and client_secret, and keeping it
     * in the clear gives back half of what encrypting it bought — an attacker with a stolen refresh
     * token and this secret can mint access tokens from anywhere. It is here because re-downloading
     * credentials.json for a dozen accounts across several orgs is the kind of pain that makes people
     * avoid resetting at all. It is one setting ({@code backupCredentialsJson}) and one verb
     * ({@code purgebackups}) away from being gone.
     */
    public static Path credentialsBackup() {
        return home().resolve("credentials-backup");
    }

    /** Where a keyring goes when its passphrase was lost — moved aside, never deleted. */
    public static Path orphanedKeyring(long stamp) {
        return home().resolve("keyring.orphaned-" + stamp + ".bin");
    }

    /**
     * Read by genuinely headless automation that has no wallet UI and no console: a DPAPI-protected
     * passphrase, inert if copied to another machine. The last honest reason anyone wanted
     * {@code --app-key} on a command line.
     */
    public static Path headlessKeyFile() {
        return home().resolve("headless.key");
    }
}

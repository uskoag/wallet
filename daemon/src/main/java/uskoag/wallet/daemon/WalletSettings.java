package uskoag.wallet.daemon;

import uskoag.wallet.wire.WalletPaths;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * The knobs that decide how much this thing gets in your way.
 *
 * <p><strong>These live inside the keyring, not in a file beside it.</strong> A settings file outside the
 * encryption is a file anything running as this user can edit, and one line of it —
 * {@code readRequiresRule = false} — turns the entire policy layer off silently. Settings that can
 * disable the controls have to be protected the way the controls are, which means they are readable and
 * writable only while the wallet is unlocked, and only through the Settings tab.
 *
 * <p>The old {@code wallet.toml} is imported once by the v4 to v5 migration and then renamed, so an
 * abandoned file cannot quietly keep winning arguments with the keyring.
 *
 * <p>Defaults are chosen for the least patient user: browsing and search never prompt, a first touch of
 * a new document prompts once and the answer is remembered, and only the irreversible tier keeps asking.
 */
public final class WalletSettings {

    public boolean readRequiresRule = true;
    public boolean mutateRequiresRule = true;

    /**
     * A development-phase convenience with a real cost, on by default while the wallet is new: see
     * {@link uskoag.wallet.wire.WalletPaths#credentialsBackup()}. Turn it off and purge once settled.
     */
    public boolean backupCredentialsJson = true;

    /**
     * Google's helper opens whatever Windows calls the default browser, which with several org accounts
     * on one machine is often signed in as the wrong one. The copyable URL window appears either way;
     * set this false if the automatic attempt is never the right browser and is just noise.
     */
    public boolean openBrowserAutomatically = true;

    /**
     * An irreversible approval re-asks for the passphrase.
     *
     * <p>Without it, unlocking once in the morning leaves every deletion and every share for the rest of
     * the day a single keypress away, which is too liberal for the one tier that cannot be undone. The
     * passphrase is the only thing here that proves a person rather than a process, since a click can be
     * synthesised by anything running as this user and the dialog cannot tell the difference.
     */
    public boolean destructiveNeedsPassphrase = true;

    public int destructiveOps = 25, destructiveMinutes = 15, autoLockMinutes = 0;

    // How long a permission of each tier may stand is NOT here. It is on uskoag.wallet.wire.Tier, fixed
    // and not overridable, because a ceiling anyone can raise is not a ceiling — and the reason it can
    // afford to be short is that extending costs one click.

    /** Values only — the object identity is shared with {@link PolicyEngine} and must not change. */
    public void copyFrom(WalletSettings o) {
        if (o == null) return;
        readRequiresRule = o.readRequiresRule;
        mutateRequiresRule = o.mutateRequiresRule;
        backupCredentialsJson = o.backupCredentialsJson;
        openBrowserAutomatically = o.openBrowserAutomatically;
        destructiveNeedsPassphrase = o.destructiveNeedsPassphrase;
        destructiveOps = o.destructiveOps;
        destructiveMinutes = o.destructiveMinutes;
        autoLockMinutes = o.autoLockMinutes;
    }

    public WalletSettings copy() {
        var s = new WalletSettings();
        s.copyFrom(this);
        return s;
    }

    /**
     * Reads the pre-v5 {@code wallet.toml} so nothing anyone had configured is silently discarded on
     * upgrade. Called once, by the migration, which then renames the file.
     */
    static WalletSettings fromLegacyToml() {
        var s = new WalletSettings();
        var f = WalletPaths.configFile();
        if (!Files.exists(f)) return s;
        try {
            var kv = flatToml(Files.readString(f));
            s.readRequiresRule = bool(kv, "readRequiresRule", s.readRequiresRule);
            s.mutateRequiresRule = bool(kv, "mutateRequiresRule", s.mutateRequiresRule);
            s.backupCredentialsJson = bool(kv, "backupCredentialsJson", s.backupCredentialsJson);
            s.openBrowserAutomatically = bool(kv, "openBrowserAutomatically", s.openBrowserAutomatically);
            s.destructiveNeedsPassphrase = bool(kv, "destructiveNeedsPassphrase", s.destructiveNeedsPassphrase);
            s.destructiveOps = num(kv, "destructiveOps", s.destructiveOps);
            s.destructiveMinutes = num(kv, "destructiveMinutes", s.destructiveMinutes);
            s.autoLockMinutes = num(kv, "autoLockMinutes", s.autoLockMinutes);
        } catch (Exception ignored) {
        }
        return s;
    }

    private static Map<String, String> flatToml(String text) {
        var kv = new HashMap<String, String>();
        for (var line : text.split("\\R")) {
            var t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("[")) continue;
            var eq = t.indexOf('=');
            if (eq < 0) continue;
            kv.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim().replaceAll("^\"|\"$", ""));
        }
        return kv;
    }

    private static boolean bool(Map<String, String> kv, String key, boolean fallback) {
        var v = kv.get(key);
        return v == null ? fallback : Boolean.parseBoolean(v);
    }

    private static int num(Map<String, String> kv, String key, int fallback) {
        try {
            return kv.containsKey(key) ? Integer.parseInt(kv.get(key)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

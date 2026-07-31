package uskoag.wallet.daemon;

import uskoag.wallet.wire.WalletPaths;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * The handful of knobs that decide how much this thing gets in your way, in a flat TOML the wallet
 * reads and a person edits.
 *
 * <p>Defaults are chosen for the least patient user: browsing and search never prompt, a first touch of
 * a new document prompts once and the answer is remembered, and only the irreversible tier keeps
 * asking. If that still turns out to be too much, {@code readRequiresRule = false} restores silent
 * reads without touching anything else.
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

    public int destructiveOps = 25, destructiveMinutes = 15, autoLockMinutes = 0;

    public static WalletSettings load() {
        var s = new WalletSettings();
        var f = WalletPaths.configFile();
        if (!Files.exists(f)) return s;
        try {
            var kv = flatToml(Files.readString(f));
            s.readRequiresRule = bool(kv, "readRequiresRule", s.readRequiresRule);
            s.mutateRequiresRule = bool(kv, "mutateRequiresRule", s.mutateRequiresRule);
            s.backupCredentialsJson = bool(kv, "backupCredentialsJson", s.backupCredentialsJson);
            s.openBrowserAutomatically = bool(kv, "openBrowserAutomatically", s.openBrowserAutomatically);
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

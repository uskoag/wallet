package uskoag.wallet.daemon;

/**
 * Carries an older keyring forward rather than refusing it.
 *
 * <p>The version guard exists because Gson will silently parse an old shape into the new one and leave
 * the moved fields unset, which looks like a working wallet and is not. Refusing is the safe default;
 * migrating explicitly is better still, and it is only better because it is explicit — each step below
 * names exactly what it fills in, so nothing is left to inference.
 */
public final class Migrate {

    private Migrate() {
    }

    /** True if the data was changed and should be written back at the new version. */
    public static boolean forward(KeyringData data) {
        var changed = false;
        if ("2".equals(data.version)) {
            two2three(data);
            data.version = "3";
            changed = true;
        }
        if ("3".equals(data.version)) {
            three2four(data);
            data.version = "4";
            changed = true;
        }
        if ("4".equals(data.version)) {
            four2five(data);
            data.version = "5";
            changed = true;
        }
        if ("5".equals(data.version)) {
            five2six(data);
            data.version = KeyringData.VERSION;
            changed = true;
        }
        return changed;
    }

    /**
     * Adopts the auto-lock default.
     *
     * <p>v5 carried the old default of 0, meaning never — so the setting existed and did nothing, which is
     * how it had been since it was written. Turning it on for keyrings that still hold that 0 is the
     * difference between the second lock working and merely being available. A stored 0 here cannot be a
     * considered choice: nothing has ever offered a way to make one.
     */
    private static void five2six(KeyringData data) {
        if (data.settings().autoLockMinutes != 0) return;
        data.settings().autoLockMinutes = 60;
        Log.info("keyring migrated 5 -> 6: auto-lock adopted at 60 idle minutes. Set it to 0 in the"
                + " Settings tab for genuinely unattended work.");
    }

    /**
     * v4 kept settings in {@code wallet.toml}, outside the encryption, where anything running as this
     * user could set {@code readRequiresRule = false} and switch the policy layer off without touching
     * the wallet. v5 keeps them in the keyring.
     *
     * <p>The old file is imported so nothing configured is lost, then renamed — an abandoned toml left
     * in place would keep looking authoritative to anyone who found it while no longer being read, which
     * is a worse outcome than deleting it outright.
     */
    private static void four2five(KeyringData data) {
        data.settings = WalletSettings.fromLegacyToml();

        // v4 could store a rule with no expiry at all. v5 caps every tier, so any surviving open-ended
        // rule is brought under its ceiling rather than grandfathered: a permission granted before the
        // ceiling existed is exactly the kind nobody remembers granting.
        var capped = 0;
        var now = System.currentTimeMillis();
        for (var r : data.rules()) {
            var tier = r.tier == null ? uskoag.wallet.wire.Tier.READ : r.tier;
            var ceiling = now + tier.maxMinutes * 60_000L;
            if (r.expiresAt <= 0 || r.expiresAt > ceiling) {
                r.expiresAt = ceiling;
                capped++;
            }
        }
        if (capped > 0) Log.info("keyring migration 4 -> 5: " + capped + " rule(s) brought under the new"
                + " per-tier expiry ceilings");
        var toml = uskoag.wallet.wire.WalletPaths.configFile();
        try {
            if (java.nio.file.Files.exists(toml)) {
                var retired = toml.resolveSibling(toml.getFileName() + ".imported-into-keyring");
                java.nio.file.Files.move(toml, retired,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Log.info("keyring migrated 4 -> 5: settings moved inside the keyring; "
                        + toml.getFileName() + " imported and renamed to " + retired.getFileName());
                return;
            }
        } catch (Exception e) {
            Log.warn("settings imported, but " + toml.getFileName() + " could not be renamed - "
                    + "delete it by hand, it is no longer read: " + e);
            return;
        }
        Log.info("keyring migrated 4 -> 5: settings now live inside the keyring");
    }

    /**
     * v2 held one union token per account with no group. v3 holds one token per scope group, so each
     * expires on its own. An existing union token cannot be split — its scopes were granted together in
     * one consent — so it becomes a single {@code legacy} token carrying exactly what it really has, and
     * sorts last so any properly scoped token granted later is preferred over it.
     */
    /**
     * v3 seeded a token's preference order from its scope <em>count</em>, which ranks power backwards:
     * {@code drive} is one scope that deletes anything, {@code docs} is three that cannot delete a file.
     * Everything therefore landed on order 1 and ties were broken arbitrarily, so a Drive read could be
     * served by the full-control token — losing the entire point of holding a narrow one. v4 re-seeds
     * from the group's declared rank.
     */
    private static void three2four(KeyringData data) {
        for (var c : data.credentials()) {
            c.order = uskoag.wallet.wire.Groups.byId(c.group)
                    .map(uskoag.wallet.wire.ScopeGroup::rank)
                    .orElse(uskoag.wallet.wire.ScopeGroup.UNKNOWN_RANK);
        }
        Log.info("keyring migrated 3 -> 4: preference order re-seeded from privilege rank");
    }

    private static void two2three(KeyringData data) {
        for (var c : data.credentials()) {
            if (c.group == null || c.group.isBlank()) c.group = AccountVerbs.LEGACY;
            if (c.order == 0) c.order = 1000;
        }
        Log.info("keyring migrated 2 -> 3: " + data.credentials().size()
                + " token(s) kept as 'legacy'. Re-consent into groups when convenient.");
    }
}

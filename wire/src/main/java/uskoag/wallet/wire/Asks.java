package uskoag.wallet.wire;

import java.util.List;

/**
 * The small request and reply shapes the control verbs use. Grouped in one file because each is two
 * lines and a file apiece would be noise.
 */
public final class Asks {

    private Asks() {
    }

    public record Unlock(String passphrase) {
    }

    public record Passwd(String current, String fresh) {
    }

    /** One OAuth client for one organisation. Domains let later accounts skip naming the org at all. */
    public record AddOrg(String id, String label, String credentialsJson, List<String> domains) {
    }

    /**
     * Consent for one account, one browser round trip per group.
     *
     * <p>{@code customScopes} is the escape hatch: a scope set nobody foresaw, typed by hand, so an app
     * we have not catalogued is still serviceable without a code change. It becomes a group named by
     * {@code customId}.
     */
    public record Login(String account, String org, List<String> groups,
                        String customId, List<String> customScopes, int port) {
    }

    public record TokenRef(String account, String group) {
    }

    public record OrgRef(String id) {
    }

    /** Renaming a client has to carry every account that points at it, or they orphan. */
    public record OrgRename(String from, String to) {
    }

    /** Replaces an org's domain patterns. Exact, {@code *.wildcard} or {@code re:} regex. */
    public record OrgDomains(String id, List<String> domains) {
    }

    /** Preference order among tokens that all satisfy a request; lower wins. */
    public record Reorder(String account, List<String> groups) {
    }

    /** {@code days} 0 means "never used at all"; above that, also anything idle beyond the window. */
    public record Unused(int days) {
    }

    /**
     * Migrate existing {@code tokens_<md5>} stores. {@code profiles} is a multi-select and defaults to
     * every known tool, because one account's tokens are scattered across several tool directories and
     * picking them off one at a time was the wrong shape.
     */
    public record Import(String org, String appKey, List<String> profiles, List<String> accounts,
                         String root, boolean deleteOld) {
    }

    public record Export(String account, boolean raw) {
    }

    public record Forget(String account) {
    }

    public record Recent(int limit) {
    }

    public record Done(boolean ok, String message) {

        public static Done yes(String message) {
            return new Done(true, message);
        }

        public static Done no(String message) {
            return new Done(false, message);
        }
    }
}

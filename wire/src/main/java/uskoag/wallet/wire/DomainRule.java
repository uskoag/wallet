package uskoag.wallet.wire;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Which addresses an OAuth client answers for.
 *
 * <p>One client routinely serves several domains — an org with a legacy domain, a country domain and a
 * vanity one is ordinary, not exotic — so this is a list of patterns rather than a single string.
 *
 * <p>Three forms, in increasing order of how rarely they are needed:
 * <ul>
 *   <li>{@code uskfoundation.or.ke} — exact domain</li>
 *   <li>{@code *.kailaasa.org} — that domain and any subdomain of it</li>
 *   <li>{@code re:^(legal|mail)\..+\.org$} — a regex, for anything the first two cannot say</li>
 * </ul>
 *
 * <p>A pattern that fails to compile matches nothing and says so, rather than throwing during a login
 * and taking the whole consent down with it.
 */
public final class DomainRule {

    public static final String REGEX_PREFIX = "re:";

    private DomainRule() {
    }

    public static boolean matches(List<String> patterns, String email) {
        if (patterns == null || email == null) return false;
        var at = email.indexOf('@');
        if (at < 0) return false;
        var domain = email.substring(at + 1).toLowerCase();
        return patterns.stream().anyMatch(p -> one(p, domain));
    }

    static boolean one(String pattern, String domain) {
        if (pattern == null || pattern.isBlank()) return false;
        var p = pattern.trim().toLowerCase();
        if (p.startsWith(REGEX_PREFIX)) {
            try {
                return Pattern.compile(p.substring(REGEX_PREFIX.length())).matcher(domain).matches();
            } catch (Exception e) {
                return false;
            }
        }
        if (p.startsWith("*.")) {
            var bare = p.substring(2);
            return domain.equals(bare) || domain.endsWith("." + bare);
        }
        return p.equals(domain);
    }

    /** Why a pattern is bad, or null when it is fine. Shown beside the field rather than on failure. */
    public static String problem(String pattern) {
        if (pattern == null || pattern.isBlank()) return "empty";
        var p = pattern.trim();
        if (p.startsWith(REGEX_PREFIX)) {
            try {
                Pattern.compile(p.substring(REGEX_PREFIX.length()));
                return null;
            } catch (Exception e) {
                return "not a valid regex: " + e.getMessage();
            }
        }
        if (p.contains("@")) return "this is a domain, not an address - drop the part before the @";
        return null;
    }
}

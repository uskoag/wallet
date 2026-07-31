package uskoag.wallet.wire;

import java.util.List;

/**
 * One consent, one token, one expiry.
 *
 * <p>Separating these is not tidiness. Google expires refresh tokens for unverified apps per token, so
 * a single union token dies when its shortest-lived scope does — an unused mail grant would otherwise
 * take Sheets, Docs and Slides down with it.
 *
 * @param rank   how much power this group carries, lower being narrower. Explicit, because counting
 *               scopes gets it backwards: {@code drive} is one scope and can delete everything, while
 *               {@code docs} is three and cannot delete a file.
 * @param scopes the exact strings sent to Google, so nothing is inferred at consent time
 * @param custom true for a group the user defined by hand, which the built-in catalogue cannot cover
 */
public record ScopeGroup(String id, String label, String detail, List<String> scopes,
                         Tier2 tier, int rank, boolean custom) {

    /** Anything hand-written or inherited sorts last: we cannot judge what it carries, so we assume much. */
    public static final int UNKNOWN_RANK = 100;

    public static ScopeGroup of(String id, String label, String detail, Tier2 tier, int rank, String... scopes) {
        return new ScopeGroup(id, label, detail, List.of(scopes), tier, rank, false);
    }

    public static ScopeGroup handWritten(String id, List<String> scopes) {
        return new ScopeGroup(id, id, "hand-written scope set", List.copyOf(scopes),
                Tier2.RESTRICTED, UNKNOWN_RANK, true);
    }

    public boolean covers(List<String> wanted) {
        return scopes.containsAll(wanted);
    }
}

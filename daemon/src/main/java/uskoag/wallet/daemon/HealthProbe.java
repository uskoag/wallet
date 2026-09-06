package uskoag.wallet.daemon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * One read-only call that proves an API still answers this credential.
 *
 * <p>Derived from the token's <b>scopes</b> and never from its group name, so a {@code legacy} token
 * imported from a pre-wallet store and a hand-written scope set are covered on the same footing as the
 * catalogue groups. One probe per API, because two calls to Gmail prove nothing a single one does not.
 *
 * <p><b>There is deliberately no probe for Sheets, Docs or Slides.</b> Those three sell no read that does
 * not name a document, and the first version of this file worked around that by fetching a document id
 * chosen to not exist and treating the 404 as a pass. It was clever and it was wrong twice over: it put
 * three network round trips on the one credential that could least afford them, and being three, one of
 * them duly hit a connect timeout in ordinary use and reported a healthy credential as unreachable. What
 * those probes were really testing — that the grant still carries the scopes — is answered directly, in one
 * call and with no document, by {@link GrantedScopes}. So the question was moved rather than answered
 * better.
 *
 * <p>What that gives up is proof that the Sheets, Docs and Slides APIs are still enabled on the Cloud
 * project. Worth stating plainly rather than papering over: a disabled API would now be found on first real
 * use instead of by the daily check. That is the correct trade, because the alternative was a check that
 * cried wolf, and a check that cries wolf is a check nobody reads.
 *
 * @param pass the HTTP codes that mean this API is fine
 */
public record HealthProbe(String api, String url, List<Integer> pass) {

    static HealthProbe ok(String api, String url) {
        return new HealthProbe(api, url, List.of(200, 204));
    }

    public boolean passed(int status) {
        return pass.contains(status);
    }

    /**
     * The probes worth running for one token, at most one per API.
     *
     * <p>An unrecognised scope contributes no probe rather than a guess. A token made only of scopes nobody
     * here has catalogued still has its refresh checked and its scopes confirmed, which is the part that
     * matters; inventing a URL for it would produce failures that say nothing about the credential.
     */
    public static List<HealthProbe> forScopes(List<String> scopes) {
        var byApi = new LinkedHashMap<String, HealthProbe>();
        for (var raw : scopes == null ? List.<String>of() : scopes) {
            var probe = forScope(raw == null ? "" : raw.trim());
            if (probe != null) byApi.putIfAbsent(probe.api, probe);
        }
        return new ArrayList<>(byApi.values());
    }

    private static HealthProbe forScope(String scope) {
        var s = scope.startsWith("https://www.googleapis.com/auth/")
                ? scope.substring("https://www.googleapis.com/auth/".length()) : scope;
        return switch (s) {
            // Exactly what the requirement asked for by name: has anything arrived. The cheapest read
            // Gmail has, and it returns messagesTotal and historyId, so it is also a real answer.
            case "gmail.readonly", "gmail.modify", "gmail.metadata", "https://mail.google.com/" ->
                    ok("gmail", "https://gmail.googleapis.com/gmail/v1/users/me/profile");
            // Settings scopes cannot read the mailbox at all, so the profile probe would 403 on a
            // perfectly healthy token. Read a setting instead.
            case "gmail.settings.basic", "gmail.settings.sharing" ->
                    ok("gmail.settings",
                            "https://gmail.googleapis.com/gmail/v1/users/me/settings/autoForwarding");
            case "drive", "drive.readonly", "drive.file", "drive.metadata",
                 "drive.metadata.readonly", "drive.appdata" ->
                    ok("drive", "https://www.googleapis.com/drive/v3/about?fields=user");
            case "youtube", "youtube.readonly", "youtube.force-ssl" ->
                    ok("youtube", "https://youtube.googleapis.com/youtube/v3/channels?part=id&mine=true");
            default -> null;
        };
    }
}

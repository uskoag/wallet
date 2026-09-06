package uskoag.wallet.daemon;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import uskoag.wallet.wire.Groups;
import uskoag.wallet.wire.Health;
import uskoag.wallet.wire.HealthRow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The once-a-day question, asked of one credential: does this still work, and if not, why not.
 *
 * <p>Three steps, in order of how much they prove. Exchanging the refresh token is the definitive test and
 * the only one that can establish the consent still exists. {@link GrantedScopes} then asks Google which
 * scopes the resulting token actually carries, which is what makes a withdrawn scope nameable. Last come the
 * per-API reads, which prove the API itself is still enabled. Nothing here writes anything anywhere.
 *
 * <p><b>The hierarchy is deliberate and it is what the first version got wrong.</b> It returned on the first
 * probe that did not answer, so one connect timeout on the third of three probes reported a credential as
 * unreachable — discarding two probes that had passed and a refresh that had succeeded. A network hiccup is
 * not a fact about a credential, and a check that turns one into a fact about a credential is a check that
 * gets ignored. So now: a verdict about the credential wins wherever it comes from; a step that merely could
 * not be reached is noted and does not overturn what the earlier steps established; and
 * {@link Health#UNREACHABLE} is reserved for failing to reach the token endpoint itself, which is the one
 * case in which genuinely nothing is known.
 *
 * <p><b>It does not go through {@link TokenCache} and does not call {@code cred.used()}.</b> Both would be
 * shorter and both would lie: the cache stamps {@code lastUsed}, so a daily ping routed through it would
 * make every token in the keyring look busy and quietly destroy "remove unused" — the one report that
 * answers which of these consents this office does not actually need. A maintenance ping is not work, and
 * it is not allowed to look like work.
 */
public final class HealthCheck {

    private static final NetHttpTransport TRANSPORT = new NetHttpTransport();

    private HealthCheck() {
    }

    /**
     * Checks one credential and records the verdict on it.
     *
     * @return true when the verdict differs from the last one, which is what decides whether this reaches
     *         the audit and the tray. A healthy credential found healthy again is not news.
     */
    static boolean check(CredentialRecord cred, OrgRecord org) {
        var now = System.currentTimeMillis();
        if (org == null || org.clientSecret == null || org.clientSecret.isBlank()) {
            return cred.healthIs(Health.CLIENT_GONE, "no OAuth client stored for org '" + cred.orgId
                    + "' — upload its credentials.json on the Clients tab", now);
        }
        if (cred.refreshToken == null || cred.refreshToken.isBlank()) {
            return cred.healthIs(Health.STALE, "no refresh token is stored for this group", now);
        }

        String bearer;
        try {
            bearer = refresh(cred, org);
            org.exercised(now);
        } catch (TokenResponseException e) {
            return cred.healthIs(fromOAuthError(errorOf(e)), oauthNote(e), now);
        } catch (IOException | RuntimeException e) {
            // The only route to UNREACHABLE. Nothing is known about the credential, and Health.UNREACHABLE
            // deliberately leaves lastHealthyAt and staleSince untouched: a machine off the network is not
            // a revoked grant.
            return cred.healthIs(Health.UNREACHABLE, "could not reach Google's token endpoint: "
                    + Why.of(e), now);
        }

        var answered = new ArrayList<String>();
        var silent = new ArrayList<String>();

        try {
            var granted = GrantedScopes.of(bearer);
            var missing = GrantedScopes.missingFrom(granted, cred.scopes());
            if (!missing.isEmpty()) {
                return cred.healthIs(Health.SCOPE_LOST, "Google no longer honours "
                        + missing.size() + " scope(s) this token was granted: " + String.join(", ", missing)
                        + ". Re-authenticate to restore them.", now);
            }
            answered.add("all " + granted.size() + " scope(s)");
        } catch (IOException e) {
            silent.add("scope list (" + Why.of(e) + ")");
        }

        for (var probe : HealthProbe.forScopes(cred.scopes())) {
            var outcome = Probes.run(probe, bearer);
            if (outcome.verdict() != null) return cred.healthIs(outcome.verdict(), outcome.note(), now);
            if (outcome.reached()) answered.add(probe.api());
            else silent.add(probe.api() + " (" + outcome.note() + ")");
        }

        return cred.healthIs(Health.HEALTHY, healthyNote(answered, silent), now);
    }

    /**
     * The refresh, done here rather than through {@link TokenCache} on purpose — see the class comment.
     * Nothing is cached, so the access token minted for a check lives only as long as the check.
     */
    private static String refresh(CredentialRecord cred, OrgRecord org) throws IOException {
        return new GoogleRefreshTokenRequest(TRANSPORT, GsonFactory.getDefaultInstance(),
                cred.refreshToken, org.clientId, org.clientSecret).execute().getAccessToken();
    }

    /**
     * Says what was proved and, separately, what could not be asked.
     *
     * <p>Both halves, because a note that mentioned only the successes would quietly overstate the check,
     * and one that led with the failures would read as a fault when the credential is fine.
     */
    private static String healthyNote(List<String> answered, List<String> silent) {
        var note = new StringBuilder("refresh token accepted");
        if (!answered.isEmpty()) note.append(", and ").append(String.join(", ", answered))
                .append(" confirmed");
        if (!silent.isEmpty()) {
            note.append(". Not reachable this time, and not held against it: ")
                    .append(String.join("; ", silent));
        }
        return note.toString();
    }

    /**
     * Google's OAuth error codes, which are the whole diagnosis and are worth keeping verbatim.
     *
     * <p>{@code invalid_grant} is the common one and covers every way a consent can end: revoked at
     * myaccount.google.com, the account's password changed, an admin removed the app — and the seven-day
     * expiry that a Cloud project still in Testing applies to every refresh token it ever issues.
     * {@code invalid_client} is a different animal and does not mean re-consent: the client itself has been
     * rejected, so every account under that org is in the same position and a new credentials.json is the
     * only fix.
     */
    private static Health fromOAuthError(String code) {
        return switch (code) {
            case "invalid_grant" -> Health.STALE;
            case "invalid_client", "unauthorized_client", "deleted_client" -> Health.CLIENT_GONE;
            case "invalid_scope" -> Health.SCOPE_LOST;
            default -> Health.BLOCKED;
        };
    }

    private static String errorOf(TokenResponseException e) {
        var details = e.getDetails();
        return details == null || details.getError() == null ? "" : details.getError();
    }

    private static String oauthNote(TokenResponseException e) {
        var details = e.getDetails();
        var code = errorOf(e);
        var described = details == null ? null : details.getErrorDescription();
        var note = "Google refused the refresh: " + (code.isEmpty() ? "HTTP " + e.getStatusCode() : code)
                + (described == null || described.isBlank() ? "" : " — " + described);
        return "invalid_grant".equals(code)
                ? note + ". The consent is gone; re-authenticate this account (Accounts tab, R)."
                : note;
    }

    /** The stored verdict, for a listing that reports rather than runs. */
    static HealthRow row(CredentialRecord cred) {
        var label = Groups.byId(cred.group).map(uskoag.wallet.wire.ScopeGroup::label).orElse(cred.group);
        var life = cred.staleSince > 0 ? cred.lifeDaysAtDeath()
                : cred.addedAt <= 0 ? 0 : (System.currentTimeMillis() - cred.addedAt) / 86_400_000L;
        var probed = new ArrayList<String>();
        probed.add("scopes");
        HealthProbe.forScopes(cred.scopes()).forEach(p -> probed.add(p.api()));
        return new HealthRow(cred.account, cred.group, label, cred.health(), cred.healthNote,
                String.join(" ", probed), cred.lastCheckedAt, cred.lastHealthyAt, cred.staleSince, life);
    }
}

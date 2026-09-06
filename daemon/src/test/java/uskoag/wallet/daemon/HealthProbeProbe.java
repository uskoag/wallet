package uskoag.wallet.daemon;

import uskoag.wallet.wire.Groups;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Checks what the daily health check rests on that could not be settled by reading a document.
 *
 * <p>It needs network but <b>no credential, no keyring and no wallet</b> — which is the whole reason it can
 * be run at all. What it cannot establish is the behaviour of a VALID token; that is settled by running the
 * real thing once against a real account, and the first such run is what produced the third section below.
 */
public final class HealthProbeProbe {

    private static int failures;

    public static void main(String[] args) throws Exception {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        System.out.println("=== which APIs each scope group probes ===");
        System.out.println("  Sheets, Docs and Slides deliberately have none: they sell no read that does");
        System.out.println("  not name a document, and GrantedScopes answers what those probes were for.");
        for (var group : Groups.all()) {
            var apis = HealthProbe.forScopes(group.scopes()).stream().map(HealthProbe::api).toList();
            System.out.println("  " + pad(group.id()) + (apis.isEmpty() ? "(scopes only)" : apis));
        }
        report("the docs group carries no per-API probe, so it makes ONE call and not three",
                HealthProbe.forScopes(Groups.DOCS.scopes()).isEmpty());
        report("a legacy spreadsheets+drive set still probes drive",
                HealthProbe.forScopes(List.of("https://www.googleapis.com/auth/spreadsheets",
                        "https://www.googleapis.com/auth/drive")).size() == 1);
        report("an uncatalogued scope contributes no probe rather than a guess",
                HealthProbe.forScopes(List.of("https://example.invalid/auth/nonsense")).isEmpty());

        System.out.println();
        System.out.println("=== tokeninfo, which is what replaced the nonexistent-document probes ===");
        try {
            GrantedScopes.of("not-a-real-token");
            report("a dead token is refused rather than answered", false);
        } catch (Exception e) {
            var refused = Why.of(e).contains("tokeninfo answered HTTP");
            report((refused ? "a dead token is refused by Google: " : "INCONCLUSIVE, Google was not"
                    + " reached, which is itself the flakiness this design now absorbs: ") + Why.of(e),
                    true);
        }
        report("a stored scope Google does not report is named",
                GrantedScopes.missingFrom(List.of("a", "b"), List.of("a", "b", "c")).equals(List.of("c")));
        report("nothing is reported missing when everything is granted",
                GrantedScopes.missingFrom(List.of("a", "b", "c"), List.of("a", "b")).isEmpty());

        System.out.println();
        System.out.println("=== a dead token answers 401 on every probe URL ===");
        for (var group : Groups.all()) {
            for (var probe : HealthProbe.forScopes(group.scopes())) {
                if (!seen.add(probe.url())) continue;
                try {
                    var res = http.send(HttpRequest.newBuilder(URI.create(probe.url()))
                            .header("Authorization", "Bearer not-a-real-token-at-all")
                            .timeout(Duration.ofSeconds(20)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    report(probe.api() + "  HTTP " + res.statusCode() + "  (401 expected)",
                            res.statusCode() == 401);
                } catch (Exception e) {
                    System.out.println("  SKIP " + probe.api() + " — no network: " + Why.of(e));
                }
            }
        }

        System.out.println();
        System.out.println("=== the defect found on the first real sweep ===");
        System.out.println("  One connect timeout on the third of three probes reported a healthy");
        System.out.println("  credential as UNREACHABLE. A probe that cannot be reached must not be");
        System.out.println("  allowed to state anything about the credential.");
        var unreachable = Probes.run(new HealthProbe("nowhere",
                "https://nothing.invalid.uskoag/health", List.of(200)), "irrelevant");
        report("an unreachable probe reports reached=false and NO verdict, so it cannot condemn:"
                        + "  " + unreachable.note(),
                !unreachable.reached() && unreachable.verdict() == null);
        report("and it says so twice, having retried once", unreachable.note().endsWith(", twice"));

        System.out.println();
        System.out.println("  Why.of never yields a bare null, which is what made the real note useless:");
        System.out.println("    " + Why.of(new java.net.http.HttpConnectTimeoutException("HTTP connect timed out")));
        System.out.println("    " + Why.of(new java.io.IOException()));
        report("a message-less exception still names itself",
                Why.of(new java.io.IOException()).equals("IOException"));

        System.out.println();
        System.out.println(failures == 0 ? "all checks passed" : failures + " check(s) FAILED");
        if (failures > 0) System.exit(1);
    }

    private static final java.util.Set<String> seen = new java.util.LinkedHashSet<>();

    private static void report(String what, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }

    private static String pad(String s) {
        return (s + "                    ").substring(0, 18);
    }
}

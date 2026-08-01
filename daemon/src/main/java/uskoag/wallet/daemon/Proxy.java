package uskoag.wallet.daemon;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import uskoag.wallet.wire.GApi;
import uskoag.wallet.wire.GrantInitializer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * The only route from a client to Google.
 *
 * <p>Classification happens here rather than in the client because this is where the request actually
 * is. Asking a client to declare its own tier before the call would mean trusting the party we are
 * specifically not trusting, and it is unknowable in advance anyway.
 */
public final class Proxy {

    /** Only a JSON body small enough to be a command gets read; media is never parsed. */
    private static final int PARSE_LIMIT = 1 << 20;

    /**
     * {@code UKAG_WALLET_TRACE=1} logs one line per request: method, path, tier, resource.
     *
     * <p>Off by default because it is one line per call and a scan makes thousands. On when you need to
     * answer "why was I asked about that?", which is otherwise close to unanswerable — the audit records
     * what the wallet decided but not the URL it decided from, and the whole classification hangs on the
     * URL. It was needed the first time a batch of creates produced one approval per file when the rules
     * say a create names no document; the audit could show the disagreement and not its cause.
     *
     * <p>Safe to leave on: a path and a method, never a body, never a header, never a token.
     */
    private static final boolean TRACE = System.getenv("UKAG_WALLET_TRACE") != null;

    private final WalletCore core;
    private final Gate gate;
    private HttpServer server;

    public Proxy(WalletCore core) {
        this.core = core;
        this.gate = new Gate(core);
    }

    public int start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64);
        server.createContext("/g/", this::handle);
        // Media uploads never arrive under /g/. The generated Google clients build them as
        // "/upload/" + SERVICE_PATH + REST_PATH — an absolute path, which GenericUrl resolves against
        // the host and not the base, so the proxy's own /g/<alias>/ prefix is discarded before the
        // request is ever sent. Without this context the JDK server answers "No context found for
        // request" and every upload through the wallet fails; with it, the alias comes from the grant
        // instead of the path. Found by trying to upload a file, which nothing had done before.
        server.createContext("/upload/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        var port = server.getAddress().getPort();
        core.proxyPort(port);
        Log.info("proxy listening on 127.0.0.1:" + port);
        return port;
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange x) {
        var arrivedAt = System.nanoTime();
        try (x) {
            var grant = core.grants.get(first(x, GrantInitializer.HEADER));
            if (grant == null) {
                // 403 and deliberately not 401, which this used to be. Every Google client treats 401
                // as "the access token expired" and runs its credential's refresh-and-retry path: the
                // response is consumed to make room for the retry, so by the time the exception reaches
                // the caller both getContent() and getDetails() are null and the wallet's own
                // "status":"WALLET" marker has been destroyed in transit. The retry cannot help either,
                // because a wallet grant is not something a client can refresh — only unlocking the
                // wallet reissues one.
                //
                // 403 is also the honest code: the caller authenticated fine, it simply has no live
                // grant. It is what every other refusal in here already returns, so a bulk caller sees
                // one shape for "the wallet said not now" instead of two. This cost two sessions —
                // a mid-run lock was being recorded as one failure per item because the marker never
                // arrived, and the code read as though it were being matched correctly.
                fail(x, 403, "no valid wallet grant on this request — the wallet is locked or the grant"
                        + " expired. Unlock it and run the same command again.");
                return;
            }
            var rawPath = x.getRequestURI().getRawPath();
            GApi api;
            String path;
            if (rawPath.startsWith("/upload/")) {
                // The alias cannot come from the path here, so it comes from the grant — which is the
                // stronger source anyway: a grant is issued for exactly one API, so an upload can only
                // ever reach the service its handle was minted for. The path is passed upstream
                // unchanged, because Google's own upload endpoint is /upload/<service path>.
                api = GApi.of(grant.api());
                path = rawPath.substring(1);
            } else {
                var raw = rawPath.substring("/g/".length());
                var slash = raw.indexOf('/');
                if (slash <= 0) {
                    fail(x, 400, "malformed proxy path");
                    return;
                }
                api = GApi.of(raw.substring(0, slash));
                path = raw.substring(slash + 1);
            }
            var query = x.getRequestURI().getRawQuery();

            var body = maybeRead(x);
            core.touch();
            var facts = new RequestFacts(api.alias, effectiveMethod(x), "/" + path, query, body);
            var classified = Rules.classify(facts);
            if (TRACE) {
                Log.info("trace " + x.getRequestMethod() + " /" + path
                        + (query == null || query.isBlank() ? "" : "?" + query)
                        + "  ->  " + classified.tier() + " " + classified.operation()
                        + " on " + classified.resource().id());
            }

            // Which token serves this is only knowable here: it depends on the API and the tier of this
            // individual call, not on the tool. Narrowest sufficient wins, so the wide tokens stay cold.
            //
            // It is settled before the policy question reaches anyone, for two reasons. There is no
            // sense asking a person to approve access the wallet then cannot serve; and the token is the
            // thing that makes the document's name knowable, without which the dialog can only show an
            // id — and an id is not a question anyone can answer.
            var held = core.keyring.tokensFor(grant.account());
            var chosen = TokenPicker.pick(held, api.alias, classified.tier()).orElse(null);
            if (chosen == null) {
                fail(x, 403, TokenPicker.explain(grant.account(), api.alias, classified.tier(), held));
                return;
            }
            var org = core.keyring.org(chosen.orgId).orElse(null);
            var bearer = core.tokens.accessToken(chosen, org);

            var decision = gate.decide(grant, classified, res -> res.isBrowse()
                    ? ResourceNames.Named.resolved(res.label(), "listing and search")
                    : core.names.resolve(api, res.id(), grant.account(), bearer));
            if (!decision.allowed()) {
                fail(x, 403, decision.why());
                return;
            }

            chosen.used();
            // Timed either side of the upstream leg so the wallet's own cost is a measured number
            // rather than an argument. Everything before this point — classification, token choice,
            // the policy question, naming the resource — is what the wallet adds; the relay itself is
            // Google's round trip and the bytes, which would be paid on any route. Subtracting the one
            // from the other answers "how much does brokering cost?" without needing a direct-to-Google
            // baseline, which these tools can no longer produce since the app-key paths were removed.
            var upstreamStart = System.nanoTime();
            Forward.relay(x, api, path, query, body, bearer, core.proxyPortValue());
            if (TRACE) {
                var now = System.nanoTime();
                var upstreamMs = (now - upstreamStart) / 1_000_000;
                var walletMs = (upstreamStart - arrivedAt) / 1_000_000;
                Log.info("timing " + x.getRequestMethod() + " /" + path
                        + "  wallet=" + walletMs + "ms  upstream=" + upstreamMs + "ms  overhead="
                        + (upstreamMs + walletMs == 0 ? "0"
                           : String.format("%.2f", 100.0 * walletMs / (walletMs + upstreamMs))) + "%");
            }
        } catch (Throwable t) {
            // Throwable, not Exception, for the same reason as ControlServer.handle: an Error escaping
            // here closes the exchange unanswered and logs nothing, so the caller sees "received no
            // bytes" and there is no trace of it afterwards. Always answer, and always leave a trace.
            Log.error("proxy failure on " + x.getRequestURI(), t);
            try {
                fail(x, 502, "wallet could not complete the call: " + t
                        + (t instanceof LinkageError
                           ? " — this wallet is running from a jar that has since been rebuilt."
                             + " Restart uskoag-wallet." : ""));
            } catch (IOException ignored) {
                // client already gone
            }
        }
    }

    /**
     * A command body is read so its verbs can be seen; anything else is left as a stream. This is the
     * line that keeps large uploads fast and keeps document contents out of the policy layer entirely.
     */
    private static byte[] maybeRead(HttpExchange x) throws IOException {
        var type = first(x, "Content-Type");
        if (type == null || !type.toLowerCase().contains("json")) return null;
        var declared = first(x, "Content-Length");
        if (declared != null && Long.parseLong(declared) > PARSE_LIMIT) return null;
        try (var in = x.getRequestBody()) {
            return in.readNBytes(PARSE_LIMIT);
        }
    }

    /**
     * What the request really is, which is not always what the HTTP line says.
     *
     * <p>The Google Java client cannot send {@code PATCH} over {@code HttpURLConnection} — the JDK
     * refuses the verb — so it sends {@code POST} carrying {@code X-HTTP-Method-Override: PATCH} and
     * Google honours the header. Classifying on the wire verb therefore read every single Drive update
     * as a create.
     *
     * <p>This was not cosmetic. A re-parent is {@code PATCH ?addParents=&removeParents=}, which the
     * rules call irreversible and gate behind the passphrase, an operation budget and a one-hour
     * ceiling. Seen as a POST it fell through to "create or upload" — an ordinary reversible edit — so
     * moving a folder tree was being waved through under the weakest of the three tiers. It also named
     * a different file on every call, which is why a batch asked once per item instead of once.
     *
     * <p>Trusting a client-supplied header to <em>widen</em> a classification would be a hole; this can
     * only ever narrow the tool's freedom, because Google acts on the same header. If the client lies
     * about the override, Google performs whatever the header says and so do we — the two cannot
     * disagree, which is the only reason reading it here is safe.
     */
    private static String effectiveMethod(HttpExchange x) {
        var override = first(x, "X-HTTP-Method-Override");
        return override == null ? x.getRequestMethod() : override.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String first(HttpExchange x, String header) {
        var v = x.getRequestHeaders().getFirst(header);
        return v == null || v.isBlank() ? null : v;
    }

    private static void fail(HttpExchange x, int status, String why) throws IOException {
        var payload = ("{\"error\":{\"code\":" + status + ",\"message\":"
                + uskoag.wallet.wire.Json.of(why) + ",\"status\":\"WALLET\"}}").getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.sendResponseHeaders(status, payload.length);
        try (var out = x.getResponseBody()) {
            out.write(payload);
        }
    }
}

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
        try (x) {
            var grant = core.grants.get(first(x, GrantInitializer.HEADER));
            if (grant == null) {
                fail(x, 401, "no valid wallet grant on this request");
                return;
            }
            var raw = x.getRequestURI().getRawPath().substring("/g/".length());
            var slash = raw.indexOf('/');
            if (slash <= 0) {
                fail(x, 400, "malformed proxy path");
                return;
            }
            var api = GApi.of(raw.substring(0, slash));
            var path = raw.substring(slash + 1);
            var query = x.getRequestURI().getRawQuery();

            var body = maybeRead(x);
            var facts = new RequestFacts(api.alias, x.getRequestMethod(), "/" + path, query, body);
            var classified = Rules.classify(facts);

            if (!gate.allows(grant, classified)) {
                fail(x, 403, "refused by wallet policy: " + classified.operation()
                        + " on " + classified.resource().display());
                return;
            }

            // Which token serves this is only knowable here: it depends on the API and the tier of this
            // individual call, not on the tool. Narrowest sufficient wins, so the wide tokens stay cold.
            var held = core.keyring.tokensFor(grant.account());
            var chosen = TokenPicker.pick(held, api.alias, classified.tier()).orElse(null);
            if (chosen == null) {
                fail(x, 403, TokenPicker.explain(grant.account(), api.alias, classified.tier(), held));
                return;
            }
            var org = core.keyring.org(chosen.orgId).orElse(null);
            chosen.used();
            Forward.relay(x, api, path, query, body, core.tokens.accessToken(chosen, org), core.proxyPortValue());
        } catch (Exception e) {
            Log.error("proxy failure on " + x.getRequestURI(), e);
            try {
                fail(x, 502, "wallet could not complete the call: " + e.getMessage());
            } catch (IOException ignored) {
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

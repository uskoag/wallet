package uskoag.wallet.daemon;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import uskoag.wallet.wire.Json;
import uskoag.wallet.wire.WalletHandshake;
import uskoag.wallet.wire.WalletPaths;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.Executors;

/**
 * The verb surface. Separate from the proxy port on purpose: one carries commands, the other carries
 * bulk traffic, and confusing the two would mean a media stream competing with an unlock.
 */
public final class ControlServer {

    private final WalletCore core;
    private final Verbs verbs;
    private final String token;
    private HttpServer server;

    public ControlServer(WalletCore core) {
        this.core = core;
        this.verbs = new Verbs(core);
        var raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public int start(int proxyPort) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32);
        server.createContext("/wallet/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        var port = server.getAddress().getPort();
        publish(port, proxyPort);
        Log.info("control listening on 127.0.0.1:" + port);
        return port;
    }

    public void stop() {
        if (server != null) server.stop(0);
        try {
            Files.deleteIfExists(WalletPaths.handshakeFile());
        } catch (IOException ignored) {
        }
    }

    private void publish(int controlPort, int proxyPort) throws IOException {
        var h = new WalletHandshake("1.0", controlPort, proxyPort, token,
                ProcessHandle.current().pid(), System.currentTimeMillis());
        Files.createDirectories(WalletPaths.home());
        Files.writeString(WalletPaths.handshakeFile(), Json.of(h), StandardCharsets.UTF_8);
        Restrict.toOwner(WalletPaths.handshakeFile());
    }

    private void handle(HttpExchange x) {
        try {
            if (!token.equals(x.getRequestHeaders().getFirst("X-Wallet-Token"))) {
                reply(x, 401, "{\"error\":\"bad wallet token\"}");
                return;
            }
            var verb = x.getRequestURI().getPath().substring("/wallet/".length());
            var body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            reply(x, 200, verbs.dispatch(verb, body));
        } catch (Exception e) {
            Log.error("control verb failed", e);
            try {
                reply(x, 500, Json.of(java.util.Map.of("error", String.valueOf(e.getMessage()))));
            } catch (IOException ignored) {
            }
        } finally {
            x.close();
        }
    }

    private static void reply(HttpExchange x, int status, String json) throws IOException {
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.sendResponseHeaders(status, bytes.length);
        try (var out = x.getResponseBody()) {
            out.write(bytes);
        }
    }
}

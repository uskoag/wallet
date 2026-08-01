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

    /**
     * Removes the handshake only if this process is the one that wrote it.
     *
     * <p>It used to delete unconditionally, and that was a live cross-process bug rather than a
     * theoretical one. A second {@code uskoag-wallet.exe} launch loses the single-instance claim,
     * exits — and JavaFX runs {@code Application.stop()} on the way out, which reached here and
     * deleted the <em>running</em> wallet's handshake file. The winner stayed alive, unlocked and
     * serving, while every tool on the machine reported the wallet as not running, because the
     * handshake is the only way a client finds it. Nothing was logged, because from each process's
     * own point of view nothing had gone wrong.
     *
     * <p>{@link SingleInstance#release()} had the guard already ({@code lock != null}); this did not.
     */
    public void stop() {
        if (server == null) return;
        server.stop(0);
        server = null;
        try {
            var file = WalletPaths.handshakeFile();
            var mine = Files.exists(file)
                    && Json.to(Files.readString(file, StandardCharsets.UTF_8), WalletHandshake.class)
                    .pid() == ProcessHandle.current().pid();
            if (mine) Files.deleteIfExists(file);
        } catch (Exception ignored) {
            // An unreadable or half-written handshake is not ours to delete either.
        }
    }

    private void publish(int controlPort, int proxyPort) throws IOException {
        var h = new WalletHandshake("1.0", controlPort, proxyPort, token,
                ProcessHandle.current().pid(), System.currentTimeMillis());
        Files.createDirectories(WalletPaths.home());
        Files.writeString(WalletPaths.handshakeFile(), Json.of(h), StandardCharsets.UTF_8);
        Restrict.toOwner(WalletPaths.handshakeFile());
    }

    /**
     * Catches {@link Throwable}, not {@link Exception}, and the difference is not theoretical.
     *
     * <p>An {@code Error} escaping here produced the worst failure this thing can have: the exchange was
     * closed unanswered, the client reported {@code HTTP/1.1 header parser received no bytes}, and
     * <b>nothing at all was written to the log</b>. Which is to say the wallet appeared to be running
     * and answering — {@code ping} and {@code status} both worked — while every {@code access} died with
     * a message naming nothing, pointing nowhere, and leaving no trace to look up afterwards.
     *
     * <p>The way it actually happens is mundane and will happen again: rebuilding the wallet replaces
     * the jar underneath the running process, and the next verb that needs a class not yet loaded gets
     * a {@code NoClassDefFoundError} instead. The jar is read lazily, so the verbs exercised at startup
     * keep working and only the untouched paths break — which is exactly why the wallet looked healthy.
     * A wallet still needs restarting after a rebuild; the point of this is that it now says so.
     */
    private void handle(HttpExchange x) {
        try {
            if (!token.equals(x.getRequestHeaders().getFirst("X-Wallet-Token"))) {
                reply(x, 401, "{\"error\":\"bad wallet token\"}");
                return;
            }
            var verb = x.getRequestURI().getPath().substring("/wallet/".length());
            var body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            reply(x, 200, verbs.dispatch(verb, body));
        } catch (Throwable t) {
            Log.error("control verb failed: " + x.getRequestURI().getPath(), t);
            try {
                reply(x, 500, Json.of(java.util.Map.of("error", describe(t))));
            } catch (IOException ignored) {
                // client already gone
            }
        } finally {
            x.close();
        }
    }

    /**
     * Several of these carry a null message, and "error: null" is barely better than the silence this
     * replaced. A {@code LinkageError} in particular says everything in its type and nothing in its
     * text, so the type is named and the likely cause is spelled out rather than left to be rediscovered.
     */
    private static String describe(Throwable t) {
        var msg = t.getMessage() == null || t.getMessage().isBlank()
                ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + t.getMessage();
        return t instanceof LinkageError
                ? msg + "  — this wallet is running from a jar that has since been rebuilt."
                        + " Restart uskoag-wallet."
                : msg;
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

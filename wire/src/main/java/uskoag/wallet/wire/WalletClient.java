package uskoag.wallet.wire;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** A client's only route to the wallet: loopback JSON verbs, never the keyring and never a token. */
public final class WalletClient {

    private static final Duration CONNECT = Duration.ofSeconds(2), CALL = Duration.ofMinutes(10);

    private final WalletHandshake handshake;
    private final HttpClient http;

    private WalletClient(WalletHandshake handshake) {
        this.handshake = handshake;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT).build();
    }

    public static Optional<WalletHandshake> readHandshake() {
        try {
            var f = WalletPaths.handshakeFile();
            if (!Files.exists(f)) return Optional.empty();
            return Optional.ofNullable(Json.to(Files.readString(f, StandardCharsets.UTF_8), WalletHandshake.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** A client for a wallet that is up and answering, or empty. */
    public static Optional<WalletClient> ifRunning() {
        return readHandshake().map(WalletClient::new).filter(WalletClient::ping);
    }

    public WalletHandshake handshake() {
        return handshake;
    }

    public boolean ping() {
        try {
            return post("ping", Map.of()) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public WalletStatus status() throws IOException, InterruptedException {
        return call("status", Map.of(), WalletStatus.class);
    }

    /**
     * The whole IPC surface for a client tool: ask for access, get a handle and a local root URL back.
     * A denial arrives as a populated {@code error}, never an exception, so a CLI can print it plainly.
     */
    public AccessGrant access(AccessRequest req) throws IOException, InterruptedException {
        return call("access", req, AccessGrant.class);
    }

    public <T> T call(String verb, Object body, Class<T> replyType) throws IOException, InterruptedException {
        var json = post(verb, body);
        if (json == null) throw new IOException("wallet returned no body for verb: " + verb);
        return Json.to(json, replyType);
    }

    public String callRaw(String verb, Object body) throws IOException, InterruptedException {
        var json = post(verb, body);
        if (json == null) throw new IOException("wallet returned no body for verb: " + verb);
        return json;
    }

    private String post(String verb, Object body) throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(handshake.controlBase() + verb))
                .timeout(CALL)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-Wallet-Token", handshake.token())
                .POST(HttpRequest.BodyPublishers.ofString(Json.of(body), StandardCharsets.UTF_8))
                .build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() == 401) {
            throw new IOException("wallet rejected the token in " + WalletPaths.handshakeFile()
                    + " — stop the wallet and retry so it rewrites the handshake");
        }
        if (res.statusCode() / 100 != 2) {
            throw new IOException("wallet HTTP " + res.statusCode() + " on '" + verb + "': " + res.body());
        }
        return res.body();
    }
}

package uskoag.wallet.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import uskoag.wallet.wire.Asks;
import uskoag.wallet.wire.Json;
import uskoag.wallet.wire.WalletClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code requests} — what the classifier last saw, and whether it still decides the same way.
 *
 * <p>The audit says what the wallet decided. This says what it decided it <em>from</em>, which is the
 * question every defect in this layer has turned on and the one thing nothing recorded. Each shape is
 * also a fixture: {@code --save} writes them out as JSON, {@code --replay} re-runs the rules over them.
 */
public final class RequestsList {

    private RequestsList() {
    }

    static int run(WalletClient client, Args a) throws Exception {
        var limit = Math.max(1, a.num("limit", 20));
        if (a.has("replay")) return replay(client, Math.max(limit, a.num("scan", 500)), a);

        var raw = client.callRaw("requests", new Asks.Recent(limit));
        if (a.has("json")) return WalletCli.out(raw);
        var rows = parse(raw);
        if (rows == null) return WalletCli.out(raw);
        if (rows.isEmpty()) {
            System.out.println("No requests recorded.");
            System.out.println("  Nothing has gone through the proxy since the wallet was unlocked, or it"
                    + " is locked now — the shapes are sealed with a key that lives in the keyring.");
            return 0;
        }
        if (a.has("save")) return save(rows, a.get("save", "."));

        System.out.println();
        System.out.println("MOST RECENT  (" + rows.size() + " shown, newest first)");
        System.out.println();
        for (var r : rows) {
            System.out.println(line(r));
            if (a.has("expand")) {
                var skeleton = r.str("skeleton");
                if (skeleton != null && !"null".equals(skeleton)) System.out.println("        " + skeleton);
            }
        }
        System.out.println();
        System.out.println("  --expand shows the body skeleton   --save <dir> writes them as fixtures");
        System.out.println("  --replay re-runs the rules over every stored shape and reports what changed");
        return 0;
    }

    private static final java.time.format.DateTimeFormatter CLOCK =
            java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private static String line(Row r) {
        // "!" marks a request the gate refused. Those are the interesting ones and they are otherwise
        // indistinguishable here from the ones that went through.
        var refused = "true".equalsIgnoreCase(r.str("allowed")) ? " " : "!";
        return String.format("%s %s %-11s %-6s %-6s n=%-5d %s%n           %s",
                Render.local(r.num("at"), CLOCK), refused, r.str("tier"), r.str("api"), r.str("method"),
                r.num("count"), r.str("operation"), path(r));
    }

    private static String path(Row r) {
        var q = r.str("query");
        return r.str("path") + (q == null || "null".equals(q) || q.isBlank() ? "" : "?" + q);
    }

    /**
     * The alarm worth having. A mismatch after a rule change is a regression caught against real traffic;
     * a mismatch with nothing changed means the recorded skeleton drops a field some rule now reads, and
     * the fix is a name in {@code Skeleton.KEEP}.
     */
    private static int replay(WalletClient client, int scan, Args a) throws Exception {
        var raw = client.callRaw("requests.replay", new Asks.Recent(scan));
        if (a.has("json")) return WalletCli.out(raw);
        var rows = parse(raw);
        if (rows == null) return WalletCli.out(raw);
        if (rows.isEmpty()) {
            System.out.println("Every recorded request still classifies exactly as it did"
                    + " (up to " + scan + " checked).");
            return 0;
        }
        System.out.println();
        System.out.println(rows.size() + " RECORDED REQUEST(S) NO LONGER CLASSIFY AS THEY DID");
        System.out.println();
        for (var r : rows) {
            System.out.println("  " + r.str("path"));
            System.out.println("    was: " + r.str("recorded"));
            System.out.println("    now: " + r.str("now"));
        }
        System.out.println();
        System.out.println("  Deliberate rule change, or a field the skeleton drops that a rule now reads.");
        return 1;
    }

    /** One file per shape, ready to be replayed into a probe without a wallet or a network. */
    private static int save(List<Row> rows, String dir) throws Exception {
        var out = Path.of(dir);
        Files.createDirectories(out);
        var n = 0;
        for (var r : rows) {
            var body = r.str("skeleton");
            if (body == null || "null".equals(body)) continue;
            var name = String.format("%d-%s-%s.json", r.num("at"), r.str("api"), r.str("method"));
            Files.writeString(out.resolve(name.replaceAll("[^A-Za-z0-9.\\-]", "_")), body,
                    StandardCharsets.UTF_8);
            n++;
        }
        System.out.println(n + " shape(s) written to " + out.toAbsolutePath());
        System.out.println("  Bodies are skeletons: keys and array lengths kept, values replaced. They"
                + " classify identically and carry no document content.");
        return 0;
    }

    private static List<Row> parse(String raw) {
        try {
            var array = Json.GSON.fromJson(raw, JsonArray.class);
            if (array == null) return null;
            return array.asList().stream().filter(JsonElement::isJsonObject)
                    .map(e -> new Row(e.getAsJsonObject())).toList();
        } catch (RuntimeException e) {
            return null;
        }
    }
}

package uskoag.wallet.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import uskoag.wallet.wire.Asks;
import uskoag.wallet.wire.Json;
import uskoag.wallet.wire.WalletClient;

import java.util.List;

/**
 * {@code audit} as lines, with a query and a stated reach.
 *
 * <p>Audit rows do not collapse — each one is a distinct event — so this verb gets the other two levers the
 * PRP offered: a limit, and filters. The default limit drops from a hundred to twenty, because "what just
 * happened" is what this answers almost every time and a hundred full records on one line is the same defect
 * {@code policy list} had.
 *
 * <p><b>Every answer states how far back it read.</b> A filtered query reads a window and reports on that
 * window, so "nothing matched" always arrives with the number of events it was true of. Without that, an
 * empty result reads as "this never happened" when it means "not in the last twenty" — the same reason
 * {@code uskoag-gdrivecli} states index coverage on every offline answer.
 */
public final class AuditList {

    private AuditList() {
    }

    static int run(WalletClient client, Args a) throws Exception {
        var limit = Math.max(1, a.num("limit", 20));
        var filtered = AuditFilter.active(a);
        // Asked for exactly what --limit says, so the passthrough is what the daemon would have replied on
        // its own. The wider scan below is a reading strategy for the rendered view, not a change of request.
        if (a.has("json")) return WalletCli.out(client.callRaw("audit", new Asks.Recent(limit)));

        var scan = Math.max(limit, a.num("scan", filtered ? 500 : limit));
        var raw = client.callRaw("audit", new Asks.Recent(scan));
        var rows = parse(raw);
        if (rows == null) return WalletCli.out(raw);

        var matched = AuditFilter.select(rows, a);
        if (matched.isEmpty()) return nothing(rows.size(), filtered);

        var shown = matched.size() > limit ? matched.subList(0, limit) : matched;
        header(matched.size(), rows.size(), limit, filtered);
        var showAccount = shown.stream().map(r -> r.str("account")).distinct().count() > 1;
        for (var r : shown) {
            System.out.println(AuditRow.of(r, showAccount));
            if (a.has("expand")) {
                var detail = AuditRow.detail(r);
                if (detail != null) System.out.println(detail);
            }
        }
        footer(a);
        return 0;
    }

    /**
     * The rows arrive as maps, not as records — see {@link Row}. A reply this cannot read is handed on
     * verbatim rather than swallowed: an unexpected shape must not turn the audit into an empty listing,
     * because an empty audit is a claim about history.
     */
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

    private static void header(int matched, int scanned, int limit, boolean filtered) {
        System.out.println();
        if (!filtered) {
            System.out.println("MOST RECENT  (" + Math.min(matched, limit) + " shown)");
        } else {
            System.out.println("MATCHED  (" + matched + " in the " + scanned + " most recent"
                    + (matched > limit ? "; newest " + limit + " shown" : "") + ")");
        }
        System.out.println();
    }

    private static int nothing(int scanned, boolean filtered) {
        if (filtered) {
            System.out.println("Nothing matched in the " + scanned + " most recent events."
                    + (scanned > 0 ? "  Read further back with --scan 5000." : ""));
            return 0;
        }
        System.out.println("No audit rows.");
        System.out.println("  A locked wallet cannot read its own history — the detail columns are encrypted"
                + " with a key that lives in the keyring. Unlock and run this again.");
        return 0;
    }

    private static void footer(Args a) {
        System.out.println();
        if (!a.has("expand")) {
            System.out.println("  --expand adds the command and the directory it ran in");
        }
        System.out.println("  filters: --deny  --verdict prompt  --since 2h  --tool gsheets  --account e"
                + "  --api drive  --tier write  --target <text>  --dir <text>  --limit N  --scan N  --json");
    }
}

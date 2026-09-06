package uskoag.wallet.daemon;

import com.arcadedb.database.Database;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The last N requests as the classifier saw them, kept so this layer can be debugged after the fact.
 *
 * <p>Every defect found in the policy layer so far was found by capturing what a Google client actually
 * sends and comparing it with what the rules assumed — a gzip header nobody knew was there, an absolute
 * URL where a path was expected, a {@code Content-Length} the rewrite left stale, a bare sheet name that
 * passed a digit test on its own name. Each cost a bespoke probe against a mock server. This makes that
 * evidence ordinary: it is already recorded, from real traffic, by the time anybody asks.
 *
 * <p>Bodies are stored as {@link Skeleton}s, not verbatim. Keys and array lengths survive because that is
 * what the rules read; values do not, because a policy layer that promises not to retain document
 * contents must not retain them in its debug log either. The skeleton still classifies identically, so it
 * doubles as a replayable fixture — {@link #replay} re-runs the rules over everything stored and reports
 * what no longer reproduces, which is both a regression test over real traffic and the thing that tells
 * you a rule has started reading a field the redaction drops.
 *
 * <p>Paths, queries and skeletons are sealed with the same per-column key the audit uses, for the same
 * reason: a list of document ids and ranges is a map of what this office is working on.
 *
 * <p>Full bodies are stored only when {@code UKAG_WALLET_CAPTURE=1}, which says so loudly at startup and
 * on every listing. That is a deliberate, visible, off-by-default exception rather than a setting, because
 * it is the one mode in which document contents are written to disk by this process.
 */
public final class Requests {

    private static final String TYPE = "Shape";

    /** How much history is worth keeping: enough to cover a batch run, bounded so it cannot grow. */
    private static final int KEEP = 1000, SWEEP_EVERY = 64;

    public static final boolean CAPTURE_BODIES = System.getenv("UKAG_WALLET_CAPTURE") != null;

    private Database db;
    private SecretKey column;
    private int since;

    public synchronized void open(Database shared, SecretKey columnKey) {
        this.db = shared;
        this.column = columnKey;
        if (db == null) return;
        if (!db.getSchema().existsType(TYPE)) db.getSchema().createDocumentType(TYPE);
        if (CAPTURE_BODIES) {
            Log.warn("UKAG_WALLET_CAPTURE is set: request bodies are being written to disk IN FULL,"
                    + " including document contents. Unset it and restart when you are done.");
        }
    }

    public synchronized void close() {
        db = null;
        column = null;
    }

    public void record(RequestFacts f, Bodies body, Classification c, boolean allowed, Grant grant) {
        var skeleton = Skeleton.of(body == null ? null : body.readable());
        var full = CAPTURE_BODIES && body != null && body.readable() != null
                ? new String(body.readable(), StandardCharsets.UTF_8) : null;
        write(f, body, c, allowed, grant, skeleton, full);
    }

    private synchronized void write(RequestFacts f, Bodies body, Classification c, boolean allowed,
                                    Grant grant, String skeleton, String full) {
        if (db == null) return;
        try {
            db.transaction(() -> {
                var d = db.newDocument(TYPE);
                d.set("at", System.currentTimeMillis());
                d.set("api", f.api());
                d.set("method", f.method());
                d.set("tier", String.valueOf(c.tier()));
                d.set("operation", c.operation());
                d.set("count", c.itemCount());
                d.set("allowed", allowed);
                d.set("bodyBytes", body == null || body.readable() == null ? 0 : body.readable().length);
                d.set("session", grant == null ? null : grant.sessionOrLabel());
                d.set("path", seal(f.path()));
                d.set("query", seal(f.query()));
                d.set("resource", seal(c.resource() == null ? null : c.resource().id()));
                d.set("skeleton", seal(skeleton));
                d.set("body", seal(full));
                d.save();
            });
            if (++since >= SWEEP_EVERY) {
                since = 0;
                sweep();
            }
        } catch (Exception ignored) {
            // A failed debug write must never fail the request it was describing.
        }
    }

    /** Newest first. */
    public synchronized List<Map<String, Object>> recent(int limit) {
        var out = new ArrayList<Map<String, Object>>();
        if (db == null) return out;
        try (var rs = db.query("sql", "SELECT FROM " + TYPE + " ORDER BY at DESC LIMIT "
                + Math.max(1, limit))) {
            while (rs.hasNext()) {
                var m = new java.util.LinkedHashMap<>(rs.next().toMap());
                for (var k : new String[]{"path", "query", "resource", "skeleton", "body"}) {
                    m.put(k, m.get(k) == null ? null : open(String.valueOf(m.get(k))));
                }
                out.add(m);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * Re-runs the rules over every stored skeleton and reports what no longer classifies as recorded.
     *
     * <p>Two different alarms, and they are worth telling apart when reading the output. A mismatch after
     * a rule change is a regression caught against real traffic rather than against a fixture somebody
     * imagined. A mismatch with no rule change means the skeleton is dropping something a rule reads, and
     * the fix is a field name in {@link Skeleton}.
     */
    public synchronized List<Map<String, Object>> replay(int limit) {
        var out = new ArrayList<Map<String, Object>>();
        for (var row : recent(limit)) {
            var skeleton = (String) row.get("skeleton");
            // A body too large to record whole cannot be expected to count the same; it says so itself.
            if (skeleton != null && skeleton.contains(Skeleton.TRUNCATED)) continue;
            var facts = new RequestFacts(String.valueOf(row.get("api")), String.valueOf(row.get("method")),
                    String.valueOf(row.get("path")),
                    row.get("query") == null ? null : String.valueOf(row.get("query")),
                    skeleton == null ? null : skeleton.getBytes(StandardCharsets.UTF_8));
            var now = Rules.classify(facts);
            var wasTier = String.valueOf(row.get("tier"));
            var wasOp = String.valueOf(row.get("operation"));
            var wasCount = ((Number) row.getOrDefault("count", 0)).intValue();
            if (wasTier.equals(String.valueOf(now.tier())) && wasOp.equals(now.operation())
                    && wasCount == now.itemCount()) {
                continue;
            }
            out.add(Map.of("at", row.get("at"), "path", String.valueOf(row.get("path")),
                    "recorded", wasTier + " / " + wasOp + " / n=" + wasCount,
                    "now", now.tier() + " / " + now.operation() + " / n=" + now.itemCount()));
        }
        return out;
    }

    /** Keeps the newest {@link #KEEP}; everything older goes. */
    private void sweep() {
        try (var rs = db.query("sql", "SELECT at FROM " + TYPE + " ORDER BY at DESC SKIP " + KEEP
                + " LIMIT 1")) {
            if (!rs.hasNext()) return;
            var cutoff = ((Number) rs.next().getProperty("at")).longValue();
            db.transaction(() -> db.command("sql", "DELETE FROM " + TYPE + " WHERE at <= " + cutoff));
        } catch (Exception ignored) {
        }
    }

    private String seal(String plain) {
        if (plain == null || column == null) return plain;
        return Base64.getEncoder().encodeToString(Aes.encrypt(plain.getBytes(StandardCharsets.UTF_8), column));
    }

    private String open(String sealed) {
        if (sealed == null || "null".equals(sealed) || column == null) return sealed;
        try {
            return new String(Aes.decrypt(Base64.getDecoder().decode(sealed), column), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(unreadable)";
        }
    }
}

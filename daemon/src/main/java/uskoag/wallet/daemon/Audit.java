package uskoag.wallet.daemon;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import uskoag.wallet.wire.WalletPaths;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The audit log, which is confidential material in its own right.
 *
 * <p>Timestamp, tool, operation, tier, verdict and counts stay in the clear so anomaly queries work
 * offline — "how many deletions in the last hour", "which tool is busiest". The target path, file name
 * and peer command line are encrypted per column, because that set is a map of what this office is
 * working on: a stolen token gets revoked, a list of matters and counterparties cannot be un-disclosed.
 *
 * <p>The column key lives in the keyring, so changing the wallet passphrase never re-encrypts history.
 * The cost, stated plainly: lose the passphrase and the detail columns go with it.
 */
public final class Audit {

    private static final String TYPE = "Event";
    private static final long RETAIN_MS = 30L * 24 * 3600 * 1000;

    private Database db;
    private SecretKey column;

    public synchronized void open(SecretKey columnKey) {
        this.column = columnKey;
        if (db != null) return;
        var factory = new DatabaseFactory(WalletPaths.auditRoot().toString());
        db = factory.exists() ? factory.open() : factory.create();
        if (!db.getSchema().existsType(TYPE)) db.getSchema().createDocumentType(TYPE);
        sweep();
    }

    public synchronized void close() {
        if (db != null && db.isOpen()) db.close();
        db = null;
        column = null;
    }

    public synchronized void record(AuditEvent e) {
        if (db == null) return;
        try {
            db.transaction(() -> {
                var d = db.newDocument(TYPE);
                d.set("at", e.at());
                d.set("tool", e.tool());
                d.set("account", e.account());
                d.set("api", e.api());
                d.set("operation", e.operation());
                d.set("tier", String.valueOf(e.tier()));
                d.set("verdict", String.valueOf(e.verdict()));
                d.set("count", e.count());
                d.set("session", e.session());
                d.set("pid", e.pid());
                d.set("target", seal(e.target()));
                d.set("peer", seal(e.peer()));
                d.save();
            });
        } catch (Exception ignored) {
            // A failed audit write must never fail the operation it was recording.
        }
    }

    /** Newest first, decrypted on demand because the wallet is unlocked while anyone is looking. */
    public synchronized List<Map<String, Object>> recent(int limit) {
        var out = new ArrayList<Map<String, Object>>();
        if (db == null) return out;
        try (var rs = db.query("sql", "SELECT FROM " + TYPE + " ORDER BY at DESC LIMIT " + limit)) {
            while (rs.hasNext()) {
                var m = new java.util.LinkedHashMap<>(rs.next().toMap());
                m.put("target", open(String.valueOf(m.get("target"))));
                m.put("peer", open(String.valueOf(m.get("peer"))));
                out.add(m);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public synchronized long countSince(long since) {
        if (db == null) return 0;
        try (var rs = db.query("sql", "SELECT count(*) AS n FROM " + TYPE + " WHERE at > " + since)) {
            return rs.hasNext() ? ((Number) rs.next().getProperty("n")).longValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public synchronized void sweep() {
        if (db == null) return;
        var cutoff = System.currentTimeMillis() - RETAIN_MS;
        try {
            db.transaction(() -> db.command("sql", "DELETE FROM " + TYPE + " WHERE at < " + cutoff));
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

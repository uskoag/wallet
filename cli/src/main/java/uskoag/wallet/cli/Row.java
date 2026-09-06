package uskoag.wallet.cli;

import com.google.gson.JsonObject;

/**
 * One row of a daemon reply that is a map rather than a typed record.
 *
 * <p>The audit's rows leave the daemon as {@code Map<String, Object>} — {@code Audit.recent} decrypts three
 * columns on the way past — and this module cannot see {@code AuditEvent} at all: it lives in the daemon,
 * which the build forbids the CLI to depend on, and duplicating the record here to get typed field access
 * would be a second copy of a schema to keep in step. So the rows are read as JSON, which needs exactly
 * this much: an absent key, a JSON null and ArcadeDB's own {@code @rid} bookkeeping all have to be
 * harmless, and a missing value has to read as empty rather than throw.
 */
public final class Row {

    final JsonObject o;

    Row(JsonObject o) {
        this.o = o;
    }

    String str(String name) {
        var v = o.get(name);
        return v == null || v.isJsonNull() ? "" : v.getAsString();
    }

    long num(String name) {
        var v = o.get(name);
        try {
            return v == null || v.isJsonNull() ? 0 : v.getAsLong();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Case-insensitive equality, with a null needle meaning "no opinion" so filters compose by &&. */
    boolean is(String name, String wanted) {
        return wanted == null || str(name).equalsIgnoreCase(wanted);
    }

    boolean contains(String name, String needle) {
        return needle == null || str(name).toLowerCase().contains(needle.toLowerCase());
    }
}

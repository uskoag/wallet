package uskoag.wallet.daemon;

import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Sheets, Slides and Docs hide their verbs inside a {@code batchUpdate} request body rather than in the
 * URL, so unlike Drive they cannot be classified by path alone.
 *
 * <p>This reads only the request <em>kinds</em> — the single key of each object in {@code requests[]} —
 * and never the values, so a document's contents are not parsed, logged or retained by the policy layer.
 */
public final class BatchRequests {

    private BatchRequests() {
    }

    public static List<String> kinds(byte[] body) {
        var kinds = new ArrayList<String>();
        if (body == null || body.length == 0) return kinds;
        try {
            var root = JsonParser.parseString(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return kinds;
            var requests = root.getAsJsonObject().getAsJsonArray("requests");
            if (requests == null) return kinds;
            for (var r : requests) {
                if (!r.isJsonObject()) continue;
                for (var k : r.getAsJsonObject().keySet()) kinds.add(k);
            }
        } catch (Exception ignored) {
            // An unparseable body is not a reason to allow it; the caller treats an empty kind list as
            // "cannot see inside", and the default for a body we cannot read is the stricter branch.
        }
        return kinds;
    }

    /** Counts entries in a top-level array, for "batchDelete 340 messages" style wording. */
    public static int countArray(byte[] body, String field) {
        try {
            var root = JsonParser.parseString(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            var arr = root.getAsJsonObject().getAsJsonArray(field);
            return arr == null ? 1 : Math.max(1, arr.size());
        } catch (Exception e) {
            return 1;
        }
    }

    public static boolean bodySays(byte[] body, String field, boolean value) {
        try {
            var root = JsonParser.parseString(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            var v = root.getAsJsonObject().get(field);
            return v != null && !v.isJsonNull() && v.getAsBoolean() == value;
        } catch (Exception e) {
            return false;
        }
    }
}

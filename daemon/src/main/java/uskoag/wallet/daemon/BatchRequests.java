package uskoag.wallet.daemon;

import com.google.gson.JsonParser;
import uskoag.wallet.wire.ResourceRef;
import uskoag.wallet.wire.Tier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Sheets, Slides and Docs hide their verbs inside a {@code batchUpdate} request body rather than in the
 * URL, so unlike Drive they cannot be classified by path alone.
 *
 * <p>This reads only the request <em>kinds</em> — the single key of each object in {@code requests[]} —
 * and never the values, so a document's contents are not parsed, logged or retained by the policy layer.
 * The one-key-per-element shape is the discovery document's union type and is the same across all three
 * services, which is why one reader serves them all.
 */
public final class BatchRequests {

    /** Past this many distinct verbs the dialog stops listing and starts counting. */
    private static final int NAMED = 4;

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

    /**
     * The whole batch judged by the worst thing in it, and said in words a person can act on.
     *
     * <p>Three things this has to get right, and the old version got none of them because it was never
     * handed a readable body. The tier is the maximum over the decomposed kinds, so one {@code deleteSheet}
     * among forty formatting changes still stops the batch. The count is the number of sub-requests rather
     * than 1, so a two-hundred-request batch charges two hundred against an operation budget instead of
     * one — the budget exists precisely to bound a runaway loop, and charging by the envelope let a loop
     * spend it a thousand at a time. And the wording names the worst kinds against the total, because
     * "batch update" is not a question anybody can answer and gets approved by reflex.
     */
    public static Classification rank(RequestFacts f, Set<String> destructive, ResourceRef res) {
        var kinds = kinds(f.body());
        if (kinds.isEmpty()) {
            return Classification.destructive(
                    "batch update — the wallet could not read this request, so it counts as irreversible",
                    res, 1);
        }
        var total = kinds.size();
        var worst = kinds.stream().filter(destructive::contains).toList();
        if (worst.isEmpty()) return Classification.mutate("batch update — " + list(kinds, total), res, total);
        return Classification.destructive("batch update — " + worst.size() + " of " + total
                + " requests delete (" + String.join(", ", worst.stream().distinct().toList()) + ")",
                res, total);
    }

    private static String list(List<String> kinds, int total) {
        var distinct = kinds.stream().distinct().toList();
        var shown = distinct.size() <= NAMED ? distinct : distinct.subList(0, NAMED);
        var more = distinct.size() - shown.size();
        return total + (total == 1 ? " request (" : " requests (") + String.join(", ", shown)
                + (more > 0 ? ", +" + more + " more" : "") + ")";
    }

    /**
     * Counts entries in a top-level array, for "batchDelete 340 messages" style wording. Assumes the
     * array is really there — an absent or unparseable field still counts as 1, never 0, because every
     * caller here uses it to size a destructive batch and under-counting one is the wrong direction to
     * be wrong in.
     */
    public static int countArray(byte[] body, String field) {
        try {
            var root = JsonParser.parseString(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            var arr = root.getAsJsonObject().getAsJsonArray(field);
            return arr == null ? 1 : Math.max(1, arr.size());
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * Size of a top-level array if present, else 0 — the opposite assumption from {@link #countArray},
     * for the different question "is this optional field even there at all" (e.g. {@code attendees[]} on
     * an event, which is usually absent and absence must read as zero, not one).
     */
    public static int arraySizeOrZero(byte[] body, String field) {
        try {
            var arr = JsonParser.parseString(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray(field);
            return arr == null ? 0 : arr.size();
        } catch (Exception e) {
            return 0;
        }
    }

    /** A top-level array of strings — {@code ranges}, {@code ids} — or empty when it cannot be read. */
    public static List<String> stringArray(byte[] body, String field) {
        var out = new ArrayList<String>();
        if (body == null || body.length == 0) return out;
        try {
            var arr = JsonParser.parseString(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray(field);
            if (arr == null) return out;
            for (var e : arr) if (e.isJsonPrimitive()) out.add(e.getAsString());
        } catch (Exception ignored) {
            // Empty means "cannot see inside", and every caller treats that as the stricter branch.
        }
        return out;
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

    /**
     * A multipart batch judged the same way: the strictest tier among its sub-requests, and the count is
     * how many there are. A hundred GETs is a read, which is what it always was and never said.
     */
    public static Classification rankEnvelope(RequestFacts f, BatchEnvelope env) {
        var subs = env.subs();
        if (subs.isEmpty()) {
            return Classification.destructive(
                    "batch — the wallet could not read what is inside it, so it counts as irreversible",
                    ResourceRef.browse(f.api()), 1);
        }
        var worst = (Classification) null;
        for (var s : subs) {
            var c = Rules.classify(s.facts(f.api()));
            if (worst == null || !worst.tier().atLeast(c.tier())) worst = c;
        }
        var tier = worst.tier();
        var what = tier == Tier.READ ? "batch of " + subs.size() + " requests, all reads"
                : "batch of " + subs.size() + " requests — worst is " + worst.operation();
        return new Classification(tier, what, worst.resource(), subs.size());
    }
}

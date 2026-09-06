package uskoag.wallet.daemon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Set;

/**
 * A request body reduced to the shape the classifier reads, with everything else taken out.
 *
 * <p>Recording requests is how the wallet gets debuggable, and recording bodies verbatim is how a policy
 * layer that promises not to retain document contents quietly starts retaining them. Every defect found
 * in this layer so far was a defect of <em>shape</em> — a gzip header, an absolute URL where a path was
 * expected, a {@code Content-Length} nobody adjusted, a sheet name mistaken for a cell reference. None of
 * them needed a single cell's value to diagnose.
 *
 * <p>So: object keys are kept, because {@code requests[]} is classified by its keys. Array lengths are
 * kept, because a batch is charged by how many things it does. Values are kept only for the handful of
 * fields the rules actually read, and everything else becomes a placeholder of the same JSON type.
 *
 * <p><b>The result is still valid JSON that classifies identically</b>, which is the property that makes
 * it worth storing: a recorded skeleton is simultaneously a debugging record and a replayable test
 * fixture. {@code walletcli requests --replay} re-runs the rules over every stored skeleton and reports
 * any that no longer produce what was recorded — so the redaction checks its own fidelity, and a rule
 * that starts reading a new field shows up as a mismatch rather than as silence.
 */
public final class Skeleton {

    /**
     * Fields whose values a rule reads. Anything not here is a placeholder.
     *
     * <p>Add to this when a rule starts reading a new field — {@code --replay} is what tells you that has
     * happened, by reporting a stored request whose classification no longer reproduces.
     */
    private static final Set<String> KEEP = Set.of(
            "range", "ranges", "trashed", "ids", "id", "sheetId", "valueInputOption",
            "addParents", "removeParents", "dimension", "sheetType");

    /** Arrays whose length is read, so their length is preserved exactly rather than capped. */
    private static final Set<String> COUNTED = Set.of("requests", "data", "ranges", "ids");

    private static final int SAMPLE = 3, MAX_DEPTH = 12, MAX_CHARS = 32768, OVERSIZE_SAMPLE = 50;

    /** Present when a counted array had to be shortened, so a replay knows not to trust its length. */
    public static final String TRUNCATED = "__truncated";

    private Skeleton() {
    }

    /**
     * The skeleton, or null when there was no body, or a note when it could not be parsed.
     *
     * <p>Always valid JSON. It used to be cut to a character limit, which for a five-hundred-range write
     * produced a fragment that no longer parsed — so the one property worth having, that a skeleton
     * counts the same as the body it replaced, was lost on exactly the largest batches. Now the elements
     * inside counted arrays are kept lean instead, and a body still too large is shortened
     * <em>structurally</em> and says so.
     */
    public static String of(byte[] readable) {
        if (readable == null || readable.length == 0) return null;
        try {
            var parsed = JsonParser.parseString(
                    new String(readable, java.nio.charset.StandardCharsets.UTF_8));
            var out = walk(parsed, null, 0, false).toString();
            if (out.length() <= MAX_CHARS) return out;

            var shortened = walk(parsed, null, 0, true);
            if (shortened.isJsonObject()) shortened.getAsJsonObject().addProperty(TRUNCATED, true);
            var small = shortened.toString();
            return small.length() <= MAX_CHARS ? small : small.substring(0, MAX_CHARS - 1) + "…";
        } catch (Exception e) {
            // Worth recording as a fact rather than dropping: an unparseable body is exactly the case that
            // routes to the strictest branch, and "which requests took that branch" is a question worth
            // being able to ask.
            return "\"(not json: " + readable.length + " bytes)\"";
        }
    }

    /**
     * @param lean inside a counted array, whose length must be preserved exactly: composite values that
     *             no rule reads collapse to empty rather than being walked. A five-hundred-element
     *             {@code data[]} then costs its ranges and nothing else
     */
    private static JsonElement walk(JsonElement e, String key, int depth, boolean lean) {
        if (depth > MAX_DEPTH) return com.google.gson.JsonNull.INSTANCE;
        if (e.isJsonObject()) {
            var out = new JsonObject();
            for (var entry : e.getAsJsonObject().entrySet()) {
                var k = entry.getKey();
                var v = entry.getValue();
                if (lean && !KEEP.contains(k) && (v.isJsonObject() || v.isJsonArray())) {
                    out.add(k, v.isJsonArray() ? new JsonArray() : new JsonObject());
                } else {
                    out.add(k, walk(v, k, depth + 1, lean));
                }
            }
            return out;
        }
        if (e.isJsonArray()) {
            var in = e.getAsJsonArray();
            var counted = key != null && COUNTED.contains(key);
            var out = new JsonArray();
            var keep = counted ? (lean ? Math.min(OVERSIZE_SAMPLE, in.size()) : in.size())
                               : Math.min(SAMPLE, in.size());
            // Elements of a counted array are always lean: the length is the fact worth keeping, and
            // walking every element of a five-hundred-range write in full is how the cap got hit.
            for (var i = 0; i < keep; i++) out.add(walk(in.get(i), key, depth + 1, lean || counted));
            return out;
        }
        if (key != null && KEEP.contains(key)) return e;
        return placeholder(e);
    }

    /**
     * Same JSON type, no content. The type is kept because a rule may one day branch on it, and a
     * skeleton whose types differ from the original would replay differently for the wrong reason.
     */
    private static JsonElement placeholder(JsonElement leaf) {
        if (leaf.isJsonNull()) return leaf;
        var p = leaf.getAsJsonPrimitive();
        if (p.isBoolean()) return new com.google.gson.JsonPrimitive(p.getAsBoolean());
        if (p.isNumber()) return new com.google.gson.JsonPrimitive(0);
        return new com.google.gson.JsonPrimitive("·");
    }
}

package uskoag.wallet.daemon;

import uskoag.wallet.wire.ResourceRef;

import java.util.Set;

/**
 * "Sheets is read/write only" is mostly true and not entirely: deleting a sheet, deleting rows or
 * columns, and clearing an unbounded range all lose data, and all three are invisible to URL matching
 * because they travel inside a {@code batchUpdate} body.
 */
public final class SheetsRules {

    private static final Set<String> DESTRUCTIVE_KINDS = Set.of(
            "deleteSheet", "deleteDimension", "deleteRange", "deleteNamedRange", "deleteProtectedRange",
            "deleteEmbeddedObject", "deleteFilterView", "deleteBanding", "deleteDimensionGroup",
            "deleteConditionalFormatRule", "deleteDeveloperMetadata", "deleteDataSource", "cutPaste");

    private SheetsRules() {
    }

    public static Classification classify(RequestFacts f) {
        var res = resource(f);
        var path = f.path();

        if (f.reads()) return Classification.read("read", res);

        if (path.endsWith(":batchClear")) return batchClear(f, res);
        if (path.endsWith(":clear")) return clear(f, res);
        if (path.endsWith("/values:batchUpdate")) {
            // Counted, because this is how a bulk write actually arrives and it was charging one
            // operation whether it wrote three ranges or five hundred. An operation budget that a single
            // call can spend arbitrarily much of is not a budget.
            var n = BatchRequests.countArray(f.body(), "data");
            return Classification.mutate("write cells in " + n + (n == 1 ? " range" : " ranges"), res, n);
        }
        if (path.contains("/values")) return Classification.mutate("write cells", res);
        if (path.endsWith(":batchUpdate")) return BatchRequests.rank(f, DESTRUCTIVE_KINDS, res);
        if (f.is("POST") && path.endsWith("/spreadsheets")) return Classification.mutate("create a spreadsheet", res);
        if (f.is("DELETE")) return Classification.destructive("delete", res, 1);
        return Classification.mutate("update", res);
    }

    /**
     * A bounded range is an ordinary write; an unbounded one — {@code Sheet1}, {@code Sheet1!A:Z} — is
     * "clear the sheet" wearing a different name, and that is the case worth stopping.
     */
    private static Classification clear(RequestFacts f, ResourceRef res) {
        var range = rangeOf(f.path());
        return bounded(range)
                ? Classification.mutate("clear " + range, res)
                : Classification.destructive("clear an unbounded range (" + range + ")", res, 1);
    }

    /**
     * The ranges of a {@code values:batchClear} are in the body, not the URL, and until the wallet could
     * read a body this fell through {@code rangeOf} — which looks for {@code /values/} and finds
     * {@code /values:batchClear} instead, returning the literal string {@code (batch)}. That has no digit
     * in it, so it was judged unbounded, so clearing three tidy ranges was IRREVERSIBLE and the dialog
     * said "clear an unbounded range ((batch))". Same family as the batchUpdate escalation: a body nobody
     * could read, producing a frightening sentence that names nothing.
     *
     * <p>Now judged on what it actually clears. All ranges bounded is an ordinary write; one unbounded
     * range is "clear the sheet" wearing a different name, and that is still the case worth stopping —
     * but it is named, so the answer can be reasoned about.
     */
    private static Classification batchClear(RequestFacts f, ResourceRef res) {
        var ranges = BatchRequests.stringArray(f.body(), "ranges");
        if (ranges.isEmpty()) {
            return Classification.destructive(
                    "clear ranges the wallet could not read, so it counts as irreversible", res, 1);
        }
        var open = ranges.stream().filter(r -> !bounded(r)).toList();
        if (open.isEmpty()) {
            return Classification.mutate("clear " + ranges.size()
                    + (ranges.size() == 1 ? " range" : " ranges"), res, ranges.size());
        }
        return Classification.destructive("clear " + ranges.size() + " ranges, " + open.size()
                + " of them unbounded (" + String.join(", ", open) + ")", res, ranges.size());
    }

    private static String rangeOf(String path) {
        var at = path.indexOf("/values/");
        if (at < 0) return "(batch)";
        var rest = path.substring(at + "/values/".length());
        var colon = rest.lastIndexOf(':');
        return java.net.URLDecoder.decode(colon > 0 ? rest.substring(0, colon) : rest,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * An A1-style reference rather than a sheet name: at most three letters, then optional digits.
     *
     * <p>Needed because a range with no {@code !} is ambiguous — {@code A1:C10} is a reference against the
     * first sheet, {@code Sheet1} is an entire sheet — and the two must not be judged the same way.
     */
    private static final java.util.regex.Pattern CELLS =
            java.util.regex.Pattern.compile("(?i)\\$?[a-z]{1,3}\\$?\\d*(:\\$?[a-z]{1,3}\\$?\\d*)?");

    /**
     * Bounded means both ends name a row number, so the blast radius is written down in the range itself.
     *
     * <p><b>A bare sheet name is never bounded, however it is spelled.</b> This tested the whole string
     * for a digit when there was no {@code !} in it — so {@code Sheet1} passed on the "1" in its own name,
     * and "clear the entire Sheet1", which is the single most destructive thing this endpoint does, was
     * classified as an ordinary reversible write and waved through by any standing write rule. The comment
     * above this method has named {@code Sheet1} as the case worth stopping since the day it was written;
     * the code never agreed with it. Found by testing a spread of range spellings rather than the one
     * spelling that was in mind.
     */
    private static boolean bounded(String range) {
        var bang = range.indexOf('!');
        var cells = bang < 0 ? range : range.substring(bang + 1);
        if (bang < 0 && !CELLS.matcher(cells).matches()) return false;
        if (!cells.contains(":")) return cells.matches(".*\\d.*");
        var ends = cells.split(":", 2);
        return ends[0].matches(".*\\d.*") && ends[1].matches(".*\\d.*");
    }

    static ResourceRef resource(RequestFacts f) {
        return Ids.after(f.path(), "/spreadsheets/", "sheets");
    }
}

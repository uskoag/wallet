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

        if (path.endsWith(":clear") || path.endsWith(":batchClear")) return clear(f, res);
        if (path.contains("/values")) return Classification.mutate("write cells", res);
        if (path.endsWith(":batchUpdate")) return batch(f, res);
        if (f.is("POST") && path.endsWith("/spreadsheets")) return Classification.mutate("create a spreadsheet", res);
        if (f.is("DELETE")) return Classification.destructive("delete", res, 1);
        return Classification.mutate("update", res);
    }

    private static Classification batch(RequestFacts f, ResourceRef res) {
        var kinds = BatchRequests.kinds(f.body());
        var bad = kinds.stream().filter(DESTRUCTIVE_KINDS::contains).toList();
        if (bad.isEmpty()) {
            return kinds.isEmpty()
                    ? Classification.destructive("batch update (body unreadable)", res, 1)
                    : Classification.mutate("batch update (" + String.join(", ", kinds) + ")", res);
        }
        return Classification.destructive(String.join(", ", bad), res, bad.size());
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

    private static String rangeOf(String path) {
        var at = path.indexOf("/values/");
        if (at < 0) return "(batch)";
        var rest = path.substring(at + "/values/".length());
        var colon = rest.lastIndexOf(':');
        return java.net.URLDecoder.decode(colon > 0 ? rest.substring(0, colon) : rest,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Bounded means both ends name a row number, so the blast radius is written down in the range itself. */
    private static boolean bounded(String range) {
        var cells = range.contains("!") ? range.substring(range.indexOf('!') + 1) : range;
        if (!cells.contains(":")) return cells.matches(".*\\d.*");
        var ends = cells.split(":", 2);
        return ends[0].matches(".*\\d.*") && ends[1].matches(".*\\d.*");
    }

    static ResourceRef resource(RequestFacts f) {
        return Ids.after(f.path(), "/spreadsheets/", "sheets");
    }
}

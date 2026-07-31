package uskoag.wallet.daemon;

import uskoag.wallet.wire.ResourceRef;

import java.util.Set;

/** Slides puts its verbs in a batchUpdate body exactly as Sheets does, so the same shape applies. */
public final class SlidesRules {

    private static final Set<String> DESTRUCTIVE_KINDS = Set.of(
            "deleteObject", "deleteText", "deleteTableRow", "deleteTableColumn");

    private SlidesRules() {
    }

    public static Classification classify(RequestFacts f) {
        var res = resource(f);
        if (f.reads()) return Classification.read("read", res);
        if (f.is("DELETE")) return Classification.destructive("delete", res, 1);
        if (f.path().endsWith(":batchUpdate")) return batch(f, res);
        if (f.is("POST")) return Classification.mutate("create a presentation", res);
        return Classification.mutate("update", res);
    }

    private static Classification batch(RequestFacts f, ResourceRef res) {
        var kinds = BatchRequests.kinds(f.body());
        var bad = kinds.stream().filter(DESTRUCTIVE_KINDS::contains).toList();
        if (bad.isEmpty()) {
            return kinds.isEmpty()
                    ? Classification.destructive("batch update (body unreadable)", res, 1)
                    : Classification.mutate("batch update (" + kinds.size() + " requests)", res);
        }
        return Classification.destructive(String.join(", ", bad), res, bad.size());
    }

    static ResourceRef resource(RequestFacts f) {
        return Ids.after(f.path(), "/presentations/", "slides");
    }

    /**
     * The full-resolution PNG export, which lives on {@code docs.google.com} and not on any API host.
     *
     * <p>Narrow on purpose. This alias was added for exactly one endpoint, so anything else arriving
     * under it is refused rather than passed through — otherwise fronting the host to keep a bearer
     * token out of a tool would have quietly bought a general-purpose tunnel to docs.google.com, and
     * that trade is not worth making.
     *
     * <p>The path is {@code presentation/d/<presId>/export/png?...}, which reads a file: a read of the
     * deck, named by the deck, so the dialog and the audit both say which presentation was rendered.
     */
    public static Classification classifyExport(RequestFacts f) {
        var path = f.path();
        var res = Ids.after(path, "/presentation/d/", "slides");
        if (!f.reads() || !path.contains("/export/")) {
            // Not a refusal the caller can talk its way out of: nothing but the export is served here.
            return Classification.destructive("a non-export request on the slides export host", res, 1);
        }
        return Classification.read("render a slide to PNG", res);
    }
}

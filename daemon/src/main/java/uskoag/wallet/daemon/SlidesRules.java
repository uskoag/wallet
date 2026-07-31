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
}

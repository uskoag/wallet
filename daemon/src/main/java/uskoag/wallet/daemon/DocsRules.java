package uskoag.wallet.daemon;

import uskoag.wallet.wire.ResourceRef;

import java.util.Set;

public final class DocsRules {

    /**
     * Empty on purpose. Every Docs edit, deletions of text included, is kept in the document's version
     * history and can be restored from it, the same as a Sheets cell edit. So a batchUpdate is an ordinary
     * edit whatever it contains; nothing in the Docs API destroys the document itself.
     */
    private static final Set<String> DESTRUCTIVE_KINDS = Set.of();

    private DocsRules() {
    }

    public static Classification classify(RequestFacts f) {
        var res = resource(f);
        if (f.reads()) return Classification.read("read", res);
        if (f.is("DELETE")) return Classification.destructive("delete", res, 1);
        if (f.path().endsWith(":batchUpdate")) return BatchRequests.rank(f, DESTRUCTIVE_KINDS, res);
        if (f.is("POST")) return Classification.mutate("create a document", res);
        return Classification.mutate("update", res);
    }

    static ResourceRef resource(RequestFacts f) {
        return Ids.after(f.path(), "/documents/", "docs");
    }
}

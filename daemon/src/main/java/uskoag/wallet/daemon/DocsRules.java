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


    /**
     * A saved version of a document, through the one URL Drive offers for it. The id travels in the query
     * ({@code Export?id=...&revision=N}), not the path. Nothing else on the host is served.
     */
    public static Classification classifyExport(RequestFacts f) {
        var res = new ResourceRef("docs", queryParam(f.query(), "id"), null);
        if (res.id() == null) res = ResourceRef.browse("docs");
        if (!f.reads() || !f.path().startsWith("/feeds/download/documents/export/Export")) {
            return Classification.destructive("a non-export request on the docs export host", res, 1);
        }
        return Classification.read("read an earlier version", res);
    }

    private static String queryParam(String query, String key) {
        if (query == null) return null;
        for (var kv : query.split("&")) {
            if (kv.startsWith(key + "=")) return java.net.URLDecoder.decode(kv.substring(key.length() + 1), java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    static ResourceRef resource(RequestFacts f) {
        return Ids.after(f.path(), "/documents/", "docs");
    }
}

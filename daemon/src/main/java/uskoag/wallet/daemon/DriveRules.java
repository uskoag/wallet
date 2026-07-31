package uskoag.wallet.daemon;

import uskoag.wallet.wire.ResourceRef;

import java.util.Set;

/**
 * Drive is the one service that is properly path-addressable, so its rules really are a table of URL
 * patterns. It is also where nearly all the irreversible operations in this office live.
 */
public final class DriveRules {

    /** Path segments that follow {@code files/} but are verbs rather than a file id. */
    private static final Set<String> NOT_AN_ID = Set.of("generateIds", "trash", "listLabels");

    private DriveRules() {
    }

    public static Classification classify(RequestFacts f) {
        var res = resource(f);
        var path = f.path();

        if (path.contains("/permissions")) return permissions(f, res);

        if (f.reads()) return Classification.read(f.queryHas("alt") ? "download" : "read", res);

        if (f.is("DELETE")) {
            if (path.endsWith("/files/trash")) return Classification.destructive("empty the trash", res, 1);
            return Classification.destructive("permanently delete", res, 1);
        }

        if (f.is("PATCH")) {
            if (f.queryHas("addParents") || f.queryHas("removeParents")) {
                return Classification.destructive("move (re-parent)", res, 1);
            }
            if (BatchRequests.bodySays(f.body(), "trashed", true)) {
                return Classification.destructive("send to trash", res, 1);
            }
            return Classification.mutate("update", res);
        }

        if (f.is("POST")) {
            if (path.endsWith("/copy")) return Classification.mutate("copy", res);
            if (path.endsWith("/watch")) return Classification.mutate("watch", res);
            return Classification.mutate("create or upload", res);
        }

        if (f.is("PUT")) return Classification.mutate("upload", res);

        return Classification.destructive(f.method().toLowerCase(), res, 1);
    }

    /**
     * Sharing sits in the destructive tier even though revoking an ACL is technically a write, because
     * un-sharing does not un-copy: once a folder has gone to an outside address, revoking later does not
     * retrieve what was downloaded in the interval.
     */
    private static Classification permissions(RequestFacts f, ResourceRef res) {
        if (f.reads()) return Classification.read("list who has access", res);
        if (f.is("DELETE")) return Classification.destructive("remove someone's access", res, 1);
        if (f.is("PATCH")) return Classification.destructive("change someone's access", res, 1);
        return Classification.destructive("SHARE with someone", res, 1);
    }

    static ResourceRef resource(RequestFacts f) {
        var path = f.path();
        var at = path.indexOf("/files/");
        if (at < 0) return ResourceRef.browse("drive");
        var rest = path.substring(at + "/files/".length());
        var slash = rest.indexOf('/');
        var id = slash < 0 ? rest : rest.substring(0, slash);
        if (id.isEmpty() || NOT_AN_ID.contains(id)) return ResourceRef.browse("drive");
        return new ResourceRef("drive", id, null);
    }
}

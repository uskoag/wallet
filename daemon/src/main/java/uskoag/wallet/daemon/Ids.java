package uskoag.wallet.daemon;

import uskoag.wallet.wire.ResourceRef;

/** Pulls the document id out of a path, stopping at whichever of {@code /} or {@code :} comes first. */
public final class Ids {

    private Ids() {
    }

    public static ResourceRef after(String path, String marker, String api) {
        var at = path.indexOf(marker);
        if (at < 0) return ResourceRef.browse(api);
        var rest = path.substring(at + marker.length());
        var end = rest.length();
        for (var i = 0; i < rest.length(); i++) {
            var c = rest.charAt(i);
            if (c == '/' || c == ':' || c == '?') {
                end = i;
                break;
            }
        }
        var id = rest.substring(0, end);
        return id.isEmpty() ? ResourceRef.browse(api) : new ResourceRef(api, id, null);
    }
}

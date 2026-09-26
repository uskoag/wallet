package uskoag.wallet.daemon;

import com.google.gson.JsonParser;
import uskoag.wallet.wire.ResourceRef;

/**
 * Drive Activity v2 has one endpoint, {@code POST v2/activity:query}. It is a POST only because the query
 * travels in the body; nothing it can do changes anything, so it is a read. The file it names comes from
 * the body's {@code itemName} ("items/<fileId>"), so an approval names the document like any other read.
 * Anything else on this host is refused.
 */
public final class DriveActivityRules {

    private DriveActivityRules() {
    }

    public static Classification classify(RequestFacts f) {
        var res = item(f);
        if (!f.is("POST") || !f.path().endsWith("/activity:query")) {
            return Classification.destructive("a request other than activity:query on the Drive Activity host", res, 1);
        }
        return Classification.read("read who changed it and when", res);
    }

    private static ResourceRef item(RequestFacts f) {
        try {
            var o = JsonParser.parseString(f.bodyText()).getAsJsonObject();
            if (o.has("itemName")) {
                var name = o.get("itemName").getAsString();
                if (name.startsWith("items/")) return new ResourceRef("drive", name.substring("items/".length()), null);
            }
        } catch (Exception ignored) {
            // an unreadable body names no document; it is judged as a listing
        }
        return ResourceRef.browse("drive");
    }
}

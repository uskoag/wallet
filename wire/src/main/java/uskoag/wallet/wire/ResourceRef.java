package uskoag.wallet.wire;

/**
 * What a request is actually about, pulled out of the URL rather than trusted from the caller.
 *
 * @param id    the document, file, presentation or mailbox; {@code null} when the call names none
 * @param label something a human can judge in a dialog, filled in from the index or the API when known
 */
public record ResourceRef(String api, String id, String label) {

    /** Listing, searching and capability calls, which name no document and are safe to be liberal about. */
    public static final String BROWSE = "(browse)";

    public static ResourceRef browse(String api) {
        return new ResourceRef(api, BROWSE, "listing and search");
    }

    public boolean isBrowse() {
        return BROWSE.equals(id);
    }

    public String display() {
        if (label != null && !label.isBlank()) return label + "  (" + id + ")";
        return id == null ? api : id;
    }
}

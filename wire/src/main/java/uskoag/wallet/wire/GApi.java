package uskoag.wallet.wire;

/**
 * The Google services the proxy fronts.
 *
 * <p>{@code rootUrl} and {@code servicePath} mirror the generated clients' own defaults, because the
 * proxy has to reassemble exactly the URL the client would otherwise have built. Drive is the odd one
 * out: it still lives under {@code www.googleapis.com} with the version in the service path, while the
 * newer services each got their own host.
 */
public enum GApi {

    DRIVE("drive", "https://www.googleapis.com/"),
    SHEETS("sheets", "https://sheets.googleapis.com/"),
    SLIDES("slides", "https://slides.googleapis.com/"),
    DOCS("docs", "https://docs.googleapis.com/"),
    GMAIL("gmail", "https://gmail.googleapis.com/"),
    YOUTUBE("youtube", "https://youtube.googleapis.com/"),
    OAUTH2("oauth2", "https://www.googleapis.com/"),

    /**
     * Calendar is the other one still under {@code www.googleapis.com} rather than its own host — the
     * generated client's own {@code Calendar.DEFAULT_ROOT_URL} / {@code DEFAULT_SERVICE_PATH}
     * ({@code "calendar/v3/"}) confirm it, same as the Drive comment above.
     */
    CALENDAR("calendar", "https://www.googleapis.com/"),

    /**
     * The one non-API host here, and it exists so that a raw access token never has to leave the wallet.
     *
     * <p>Slides has no API for a full-resolution page render. The only route is an undocumented endpoint
     * on {@code docs.google.com} that takes a plain bearer header, which left uskoag-gslides needing an
     * actual Google token in its own process — the single thing this design does not allow. Fronting the
     * host closes that: the tool gets the same loopback handle as for everything else, and the request is
     * classified and recorded like any other.
     *
     * <p>Deliberately a separate alias rather than a second root for {@code slides}. It is a different
     * host with a different URL shape, and {@link uskoag.wallet.daemon} refuses anything under it that is
     * not the export path, so a general-purpose passthrough to docs.google.com is not what got added.
     */
    SLIDES_EXPORT("slidesexport", "https://docs.google.com/");

    public final String alias, upstream;

    GApi(String alias, String upstream) {
        this.alias = alias;
        this.upstream = upstream;
    }

    public static GApi of(String alias) {
        for (var a : values()) if (a.alias.equalsIgnoreCase(alias)) return a;
        throw new IllegalArgumentException("unknown google api alias: " + alias);
    }
}

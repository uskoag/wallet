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
    OAUTH2("oauth2", "https://www.googleapis.com/");

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

package uskoag.wallet.daemon;

import uskoag.wallet.wire.ResourceRef;

/** One entry point over the per-service tables, plus a conservative default for anything unrecognised. */
public final class Rules {

    private Rules() {
    }

    public static Classification classify(RequestFacts f) {
        // Before the per-service tables, because a batch envelope is not a request to any of them — it is
        // a container, and classifying the container by its own POST verb called a hundred GETs a write.
        if (f.is("POST") && BatchEnvelope.isEnvelope(f.path())) {
            return BatchRequests.rankEnvelope(f, BatchEnvelope.of(f.body(), f.api()));
        }
        return switch (f.api()) {
            case "drive" -> DriveRules.classify(f);
            case "sheets" -> SheetsRules.classify(f);
            case "slides" -> SlidesRules.classify(f);
            case "slidesexport" -> SlidesRules.classifyExport(f);
            case "docsexport" -> DocsRules.classifyExport(f);
            case "driveactivity" -> DriveActivityRules.classify(f);
            case "docs" -> DocsRules.classify(f);
            case "gmail" -> GmailRules.classify(f);
            case "calendar" -> CalendarRules.classify(f);
            default -> fallback(f);
        };
    }

    /**
     * An api nobody has written rules for still gets sensible treatment: reads are reads, deletes are
     * irreversible, and everything in between is a mutation. Unknown must never mean unrestricted.
     */
    private static Classification fallback(RequestFacts f) {
        var res = ResourceRef.browse(f.api());
        if (f.reads()) return Classification.read("read", res);
        if (f.is("DELETE")) return Classification.destructive("delete", res, 1);
        return Classification.mutate(f.method().toLowerCase(), res);
    }
}

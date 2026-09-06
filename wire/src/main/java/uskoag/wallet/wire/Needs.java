package uskoag.wallet.wire;

import java.util.List;

/**
 * What a single request actually needs, chosen per request rather than per tool.
 *
 * <p>This is the part that makes separate tokens work at all. A tool is not one scope set: uskoag-gslides
 * edits a deck (presentations), exports it (drive.readonly) and uploads an image (drive.file), and no
 * single group covers all three. But every proxied request goes to exactly one API with exactly one
 * tier, so the wallet can pick the narrowest token that serves *that* request and leave the wider ones
 * cold.
 *
 * <p>Each entry lists acceptable alternatives, widest last. The picker takes the first one the account
 * actually holds.
 */
public final class Needs {

    private Needs() {
    }

    public static List<String> forRequest(String api, Tier tier) {
        return switch (api) {
            case "sheets" -> List.of("https://www.googleapis.com/auth/spreadsheets");
            case "docs" -> List.of("https://www.googleapis.com/auth/documents");
            case "slides" -> List.of("https://www.googleapis.com/auth/presentations");
            // Rendering a deck to PNG reads the file, so it is a Drive read and not a presentations
            // edit. Asking for the narrower thing is the point: an export must never be served by a
            // token that could also rewrite the deck it is rendering.
            case "slidesexport" -> List.of("https://www.googleapis.com/auth/drive.readonly");
            // Only the full scope is ever issued (see Groups.CALENDAR_FULL) — no narrower calendar.events
            // token exists yet to prefer for a plain read, so every tier needs the same one scope.
            case "calendar" -> List.of("https://www.googleapis.com/auth/calendar");
            case "gmail" -> tier == Tier.READ
                    ? List.of("https://www.googleapis.com/auth/gmail.readonly")
                    : List.of("https://www.googleapis.com/auth/gmail.modify");
            case "drive" -> switch (tier) {
                case READ -> List.of("https://www.googleapis.com/auth/drive.readonly");
                // Was drive.file, and that was wrong in a way only a real call showed.
                //
                // drive.file is not "write, narrowly". It is per-FILE access to files the application
                // itself created or the user explicitly opened with it, and that provenance is tracked
                // by Google, not by us. So the wallet cannot know whether it suffices for a given
                // request — and for a tool whose entire job is operating on Drive content it did not
                // create, it essentially never does. Renaming an existing document, or creating one
                // inside an existing folder, both fail with ACCESS_TOKEN_SCOPE_INSUFFICIENT: the parent
                // is not a folder this app owns.
                //
                // Preferring it was therefore a guess that loses almost every time, and losing costs a
                // hard 403 carrying Google's wording rather than ours. What the narrowing was supposed
                // to buy is still bought where it is real: a READ is served by drive.readonly and the
                // full-control token stays cold for the overwhelming majority of traffic. Tier still
                // decides what is asked of a person; this only decides which token carries the call.
                case MUTATE -> List.of("https://www.googleapis.com/auth/drive");
                // Google sells no write-without-delete, so anything irreversible needs the full scope.
                case DESTRUCTIVE -> List.of("https://www.googleapis.com/auth/drive");
            };
            default -> List.of();
        };
    }

    /**
     * A wider scope that also satisfies the requirement, so an account holding only the broad token is
     * still served rather than being told to consent again for something it can already do.
     */
    public static List<String> alternatives(String api, Tier tier) {
        return switch (api) {
            case "drive", "slidesexport" -> List.of("https://www.googleapis.com/auth/drive");
            case "gmail" -> List.of("https://www.googleapis.com/auth/gmail.modify");
            default -> List.of();
        };
    }
}

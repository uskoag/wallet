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
            case "gmail" -> tier == Tier.READ
                    ? List.of("https://www.googleapis.com/auth/gmail.readonly")
                    : List.of("https://www.googleapis.com/auth/gmail.modify");
            case "drive" -> switch (tier) {
                case READ -> List.of("https://www.googleapis.com/auth/drive.readonly");
                case MUTATE -> List.of("https://www.googleapis.com/auth/drive.file");
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
            case "drive" -> List.of("https://www.googleapis.com/auth/drive");
            case "gmail" -> List.of("https://www.googleapis.com/auth/gmail.modify");
            default -> List.of();
        };
    }
}

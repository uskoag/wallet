package uskoag.wallet.wire;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The known tools, and which groups each one wants granted.
 *
 * <p>A profile no longer names a credential — that job moved to the account, and the scope set moved to
 * {@link ScopeGroup}. What survives is a suggestion at consent time ("granting these covers gslides")
 * and a label in the policy and the audit.
 *
 * <p>The suggested set is what the tool needs across all its verbs; the wallet still picks the narrowest
 * token per individual request, so granting all of them does not mean the widest is used for everything.
 */
public final class Profiles {

    public static final String GSHEETS = "gsheets", GDRIVE = "gdrive", GMAIL = "gmail", GSLIDES = "gslides";

    private static final Map<String, List<String>> SUGGESTED = Map.of(
            GSHEETS, List.of(Groups.DOCS.id()),
            GSLIDES, List.of(Groups.DOCS.id(), Groups.DRIVE_FILE.id(), Groups.DRIVE_READ.id()),
            GMAIL, List.of(Groups.MAIL_WRITE.id()),
            GDRIVE, List.of(Groups.DRIVE_READ.id(), Groups.DRIVE_FULL.id()));

    private Profiles() {
    }

    public static List<String> suggested(String profile) {
        return SUGGESTED.getOrDefault(profile, List.of(Groups.DOCS.id()));
    }

    public static List<String> known() {
        return SUGGESTED.keySet().stream().sorted().toList();
    }

    /** Where this tool's legacy {@code <email>/tokens_<md5>} directories live. Only import reads these. */
    public static Path legacyRoot(String profile) {
        var home = Path.of(System.getProperty("user.home"), "uskoag");
        return switch (profile) {
            case GSHEETS -> home.resolve("gservices").resolve("spreadsheet_cli");
            case GSLIDES -> home.resolve("gservices").resolve("gslides_cli");
            case GDRIVE -> home.resolve("gdrive");
            default -> home.resolve("gdrive_gdocs_auth");
        };
    }
}

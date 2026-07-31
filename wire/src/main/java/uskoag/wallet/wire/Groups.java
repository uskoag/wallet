package uskoag.wallet.wire;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The built-in groups, settled with the user against what Google actually sells.
 *
 * <p>Two separations he wanted are not purchasable and are enforced by the proxy instead: there is no
 * Drive scope that writes without also deleting and sharing, and no Gmail scope that drafts without
 * also sending. Both are handled by the DESTRUCTIVE tier at the request, which is a stronger control
 * anyway because it does not depend on a scope existing.
 *
 * <p>The line between DOCS and DRIVE is "does doing this properly need Drive". Listing revisions and
 * reading {@code modifiedTime} have no Sheets equivalent at all. Creation is subtler and lands the same
 * way: {@code spreadsheets.create} works without Drive, but it can only drop the file in My Drive root,
 * since {@code parents} is a Drive field — so a create that cannot be filed is half a feature and
 * belongs with Drive. That is what keeps the everyday token free of any Drive scope at all, which is
 * the single biggest reduction available in both consent-screen alarm and expiry exposure.
 */
public final class Groups {

    public static final ScopeGroup DOCS = ScopeGroup.of("docs",
            "Sheets, Docs & Slides — edit existing",
            "Open and edit any spreadsheet, document or presentation BY ID. Cannot search Drive, list"
                    + " folders, download, delete — or create. Every edit has version history."
                    + " Creation lives with Drive on purpose: the scope can make a file but cannot put it"
                    + " in a folder, because parents is a Drive field, and a file that lands in My Drive"
                    + " root is not created correctly.",
            Tier2.SENSITIVE,
            10,
            "https://www.googleapis.com/auth/spreadsheets",
            "https://www.googleapis.com/auth/documents",
            "https://www.googleapis.com/auth/presentations");

    public static final ScopeGroup DRIVE_READ = ScopeGroup.of("drive.read",
            "Drive: read everything",
            "Search by name, list folders, download and export, read revision history and modified times."
                    + " Reads every file the account can reach.",
            Tier2.RESTRICTED,
            30,
            "https://www.googleapis.com/auth/drive.readonly");

    public static final ScopeGroup DRIVE_FILE = ScopeGroup.of("drive.file",
            "Drive: only files this tool touches",
            "Create and edit files it made, or that you opened with it. Bounded by which files, not by"
                    + " which operations. No consent warning and no user cap.",
            Tier2.NONE,
            20,
            "https://www.googleapis.com/auth/drive.file");

    public static final ScopeGroup DRIVE_FULL = ScopeGroup.of("drive.full",
            "Drive: full control",
            "Create files in the right folder, write, move, trash, delete and SHARE anything. Nothing"
                    + " narrower exists — Google sells no write-without-delete. Deletion and sharing are"
                    + " gated by the wallet, not the scope.",
            Tier2.RESTRICTED,
            60,
            "https://www.googleapis.com/auth/drive");

    public static final ScopeGroup MAIL_READ = ScopeGroup.of("mail.read",
            "Mail: read only",
            "Read messages, threads, labels and settings. Cannot change or send anything.",
            Tier2.RESTRICTED,
            30,
            "https://www.googleapis.com/auth/gmail.readonly");

    public static final ScopeGroup MAIL_WRITE = ScopeGroup.of("mail.write",
            "Mail: read, label, draft & send",
            "Everything except permanent delete. SENDING IS INCLUDED — no Gmail scope separates drafting"
                    + " from sending. The wallet gates send at the request instead.",
            Tier2.RESTRICTED,
            50,
            "https://www.googleapis.com/auth/gmail.modify");

    public static final ScopeGroup MAIL_SETTINGS = ScopeGroup.of("mail.settings",
            "Mail: mailbox settings",
            "Forwarding, filters and delegation. An auto-forward rule is a standing exfiltration channel,"
                    + " which is why this is never bundled with anything else. Grant almost never.",
            Tier2.RESTRICTED,
            70,
            "https://www.googleapis.com/auth/gmail.settings.basic",
            "https://www.googleapis.com/auth/gmail.settings.sharing");

    private Groups() {
    }

    public static List<ScopeGroup> all() {
        return List.of(DOCS, DRIVE_READ, DRIVE_FILE, DRIVE_FULL, MAIL_READ, MAIL_WRITE, MAIL_SETTINGS);
    }

    /** The display tree: heading, then its groups. Order is the order the Grant dialog shows. */
    public static List<String> headings() {
        return List.of("Documents", "Drive", "Mail");
    }

    public static List<ScopeGroup> under(String heading) {
        return switch (heading) {
            case "Documents" -> List.of(DOCS);
            case "Drive" -> List.of(DRIVE_FILE, DRIVE_READ, DRIVE_FULL);
            case "Mail" -> List.of(MAIL_READ, MAIL_WRITE, MAIL_SETTINGS);
            default -> List.of();
        };
    }

    public static Optional<ScopeGroup> byId(String id) {
        return all().stream().filter(g -> g.id().equals(id)).findFirst();
    }

    /** Every group that fully covers the wanted scopes, narrowest first. */
    public static List<ScopeGroup> covering(List<String> wanted) {
        var out = new ArrayList<>(all().stream().filter(g -> g.covers(wanted)).toList());
        out.sort(java.util.Comparator.comparingInt(ScopeGroup::rank));
        return out;
    }
}

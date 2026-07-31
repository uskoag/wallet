package uskoag.wallet.wire;

import java.util.List;
import java.util.Locale;

/**
 * A searchable list of Google scopes, offered as suggestions and never as the boundary.
 *
 * <p>The seven built-in {@link Groups} cover what this office does every day. This is the other case:
 * something new is needed, it is not in a group, and the only route was to type a scope URL exactly
 * right from memory into a free-text box — where one wrong character produces a consent screen that
 * fails with an error naming nothing useful.
 *
 * <p><b>This catalogue is expected to go stale, and the design assumes it.</b> Google adds scopes
 * continually and this file will not keep up, so it is deliberately a typing aid and nothing more:
 * picking from it writes into the same free-text box a person could have typed, and that box accepts
 * anything whether it appears here or not. Nothing is refused for being absent from this list. A
 * catalogue that gated what could be asked for would be worse than no catalogue at all, because it
 * would be wrong within months and there would be no way past it.
 *
 * <p>The {@code sensitive} and {@code restricted} markings follow Google's own classification of what
 * its verification process demands, which is the thing worth knowing before triggering a consent
 * screen. They are advisory here for the same reason as everything else on this page.
 */
public final class ScopeCatalogue {

    /**
     * @param url   the scope as Google spells it, which is the only part that has to be exact
     * @param what  what consenting to it actually permits, in plain words
     * @param tier  Google's own classification, which decides how alarming the consent screen looks
     */
    public record Entry(String url, String what, Tier2 tier) {

        /** How it reads in the list: the plain meaning first, because that is what is being chosen. */
        public String display() {
            return what + "   —   " + url.replace("https://www.googleapis.com/auth/", "");
        }

        boolean matches(String needle) {
            return url.toLowerCase(Locale.ROOT).contains(needle)
                    || what.toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    private static final String A = "https://www.googleapis.com/auth/";

    private static final List<Entry> ALL = List.of(
            // ---- Drive -----------------------------------------------------------------------
            e("drive.readonly", "Drive — read every file and its metadata", Tier2.RESTRICTED),
            e("drive.file", "Drive — only files this app itself created or you opened with it", Tier2.NONE),
            e("drive.metadata.readonly", "Drive — names, folders and properties, never content", Tier2.RESTRICTED),
            e("drive.appdata", "Drive — a hidden per-application data folder", Tier2.NONE),
            e("drive.photos.readonly", "Drive — read photos stored in Drive", Tier2.RESTRICTED),
            e("drive", "Drive — FULL control: read, change, share and permanently delete anything",
                    Tier2.RESTRICTED),

            // ---- Editors ---------------------------------------------------------------------
            e("spreadsheets.readonly", "Sheets — read spreadsheets", Tier2.SENSITIVE),
            e("spreadsheets", "Sheets — read and write spreadsheets", Tier2.SENSITIVE),
            e("documents.readonly", "Docs — read documents", Tier2.SENSITIVE),
            e("documents", "Docs — read and write documents", Tier2.SENSITIVE),
            e("presentations.readonly", "Slides — read presentations", Tier2.SENSITIVE),
            e("presentations", "Slides — read and write presentations", Tier2.SENSITIVE),
            e("forms.body", "Forms — create and edit forms", Tier2.SENSITIVE),
            e("forms.body.readonly", "Forms — read form structure", Tier2.SENSITIVE),
            e("forms.responses.readonly", "Forms — read the responses people submitted", Tier2.SENSITIVE),

            // ---- Gmail -----------------------------------------------------------------------
            e("gmail.readonly", "Gmail — read all mail; never send, change or delete", Tier2.RESTRICTED),
            e("gmail.metadata", "Gmail — headers and labels only, never message bodies", Tier2.RESTRICTED),
            e("gmail.labels", "Gmail — create and apply labels", Tier2.NONE),
            e("gmail.compose", "Gmail — create and edit drafts, and send them", Tier2.RESTRICTED),
            e("gmail.send", "Gmail — SEND mail as you (no reading)", Tier2.SENSITIVE),
            e("gmail.modify", "Gmail — read, label and modify; no permanent delete", Tier2.RESTRICTED),
            e("gmail.settings.basic", "Gmail — signatures, vacation responder, filters", Tier2.RESTRICTED),
            e("gmail.settings.sharing", "Gmail — delegation and send-as, which can grant others access",
                    Tier2.RESTRICTED),
            e("https://mail.google.com/", "Gmail — FULL control including PERMANENT deletion",
                    Tier2.RESTRICTED),

            // ---- Calendar and contacts -------------------------------------------------------
            e("calendar.readonly", "Calendar — read calendars and events", Tier2.SENSITIVE),
            e("calendar.events.readonly", "Calendar — read events only", Tier2.SENSITIVE),
            e("calendar.events", "Calendar — read and change events", Tier2.SENSITIVE),
            e("calendar", "Calendar — full access to calendars and their settings", Tier2.SENSITIVE),
            e("contacts.readonly", "Contacts — read your contacts", Tier2.RESTRICTED),
            e("contacts", "Contacts — read and change your contacts", Tier2.RESTRICTED),
            e("directory.readonly", "Contacts — read the organisation's shared directory", Tier2.SENSITIVE),

            // ---- Tasks, Keep, Chat -----------------------------------------------------------
            e("tasks.readonly", "Tasks — read task lists", Tier2.SENSITIVE),
            e("tasks", "Tasks — read and change task lists", Tier2.SENSITIVE),
            e("chat.messages.readonly", "Chat — read messages in spaces this account is in", Tier2.RESTRICTED),
            e("chat.messages", "Chat — read and send messages", Tier2.RESTRICTED),
            e("chat.spaces.readonly", "Chat — list spaces and members", Tier2.SENSITIVE),

            // ---- YouTube ---------------------------------------------------------------------
            e("youtube.readonly", "YouTube — read your account's channels, playlists and videos",
                    Tier2.SENSITIVE),
            e("youtube", "YouTube — manage the account: playlists, subscriptions, channel settings",
                    Tier2.SENSITIVE),
            e("youtube.upload", "YouTube — upload videos", Tier2.SENSITIVE),
            e("youtube.force-ssl", "YouTube — full access over SSL, including comments and captions",
                    Tier2.SENSITIVE),
            e("youtubepartner", "YouTube — content-owner operations: claims, assets, rights",
                    Tier2.SENSITIVE),

            // ---- Photos ----------------------------------------------------------------------
            e("photoslibrary.readonly", "Photos — read the library", Tier2.RESTRICTED),
            e("photoslibrary.appendonly", "Photos — add new items, never read existing ones", Tier2.SENSITIVE),

            // ---- Identity --------------------------------------------------------------------
            e("userinfo.email", "Identity — which email address this is", Tier2.NONE),
            e("userinfo.profile", "Identity — the account's name and picture", Tier2.NONE),
            e("openid", "Identity — sign in with this Google account", Tier2.NONE),

            // ---- Workspace administration ----------------------------------------------------
            e("admin.directory.user.readonly", "Admin — read the directory of users", Tier2.RESTRICTED),
            e("admin.directory.user", "Admin — create, change and suspend users", Tier2.RESTRICTED),
            e("admin.directory.group.readonly", "Admin — read groups and their members", Tier2.RESTRICTED),
            e("admin.directory.group", "Admin — create and change groups and membership", Tier2.RESTRICTED),
            e("admin.reports.audit.readonly", "Admin — read the Workspace audit log", Tier2.RESTRICTED),
            e("apps.groups.settings", "Admin — read and change Google Groups settings", Tier2.SENSITIVE),

            // ---- Cloud and scripting ---------------------------------------------------------
            e("cloud-platform", "Cloud — FULL access to every Google Cloud resource this account can reach",
                    Tier2.RESTRICTED),
            e("cloud-platform.read-only", "Cloud — read every Google Cloud resource", Tier2.RESTRICTED),
            e("devstorage.read_only", "Cloud Storage — read buckets and objects", Tier2.SENSITIVE),
            e("devstorage.read_write", "Cloud Storage — read and write buckets and objects", Tier2.SENSITIVE),
            e("script.projects", "Apps Script — create and change script projects", Tier2.SENSITIVE),
            e("script.deployments", "Apps Script — create and change deployments", Tier2.SENSITIVE));

    private ScopeCatalogue() {
    }

    public static List<Entry> all() {
        return ALL;
    }

    /**
     * Entries matching every whitespace-separated term, in the URL or the description.
     *
     * <p>All terms must match rather than any, because the useful searches here are two words wide —
     * "drive read", "gmail send" — and an any-match turns those into most of the list.
     */
    public static List<Entry> search(String query) {
        if (query == null || query.isBlank()) return ALL;
        var terms = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        return ALL.stream()
                .filter(entry -> {
                    for (var t : terms) if (!entry.matches(t)) return false;
                    return true;
                })
                .toList();
    }

    private static Entry e(String tail, String what, Tier2 tier) {
        return new Entry(tail.startsWith("http") ? tail : A + tail, what, tier);
    }
}

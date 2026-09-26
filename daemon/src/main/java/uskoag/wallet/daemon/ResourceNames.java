package uskoag.wallet.daemon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import uskoag.wallet.wire.GApi;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns an opaque Google id into something a person can actually judge, using the same token that would
 * serve the request.
 *
 * <p>This exists because a dialog reading "approve read on 1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCd"
 * is not a control. Nobody can evaluate that, so everybody approves it, and the habit of approving is
 * the exact thing the dialog was built to prevent. A prompt that cannot be read is worse than no prompt,
 * because it manufactures consent and files it as a decision.
 *
 * <p>The honest cost: one metadata call goes to Google before anyone is asked. Three things keep that
 * proportionate. It is made with the account's own token, so it claims no power the account does not
 * already hold. It is field-masked to a title, so no document content is fetched. And it happens only
 * where a human was about to be asked — never on the path of a request a standing rule already covers —
 * which is also why it cannot appear in the throughput numbers.
 *
 * <p>A refusal here is information rather than an obstacle. If Google will not name the file for this
 * token, the token cannot read the file either, so the request was going to fail regardless. Being told
 * "that account cannot see this" beats being asked to approve access that does not exist.
 */
public final class ResourceNames {

    /** Long enough that a burst of prompts costs one call, short enough that a rename surfaces. */
    private static final long POSITIVE_MS = 600_000, NEGATIVE_MS = 60_000;

    /** A name is worth a moment's wait and never worth stalling a request over. */
    private static final Duration TIMEOUT = Duration.ofSeconds(6);

    private static final HttpClient LOOKUP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public enum Status {

        /** Google named it. The dialog can be read. */
        RESOLVED,

        /** Google refused to name it for this token — 404 or 403. The request cannot succeed either. */
        UNREACHABLE,

        /** Nothing was learned: no endpoint, a timeout, an unexpected shape. Ask anyway, and say so. */
        UNKNOWN
    }

    /**
     * @param name   what to show, when there is one
     * @param detail the kind of thing for a resolved name ("folder", "spreadsheet"), otherwise why the
     *               lookup came back empty — which the dialog shows verbatim, since "the name could not
     *               be resolved" without a reason is its own kind of unreadable prompt
     */
    public record Named(Status status, String name, String detail) {

        public static Named resolved(String name, String detail) {
            return new Named(Status.RESOLVED, name, detail);
        }

        public static Named unreachable(String why) {
            return new Named(Status.UNREACHABLE, null, why);
        }

        public static Named unknown(String why) {
            return new Named(Status.UNKNOWN, null, why);
        }
    }

    private record Cached(Named named, long at) {
    }

    public Named resolve(GApi api, String id, String account, String bearer) {
        var key = account + "|" + api.alias + "|" + id;
        var now = System.currentTimeMillis();
        var hit = cache.get(key);
        var ttl = hit != null && hit.named().status() == Status.RESOLVED ? POSITIVE_MS : NEGATIVE_MS;
        if (hit != null && now - hit.at() < ttl) return hit.named();

        var fresh = lookUp(api, id, account, bearer);
        cache.put(key, new Cached(fresh, now));
        return fresh;
    }

    /**
     * The name if it is already known, without asking Google.
     *
     * <p>For the paths that must not pay a lookup — chiefly a request a standing rule already covers, which
     * is the whole point of the rule. Usually warm, because the first touch of a document resolved it.
     */
    public java.util.Optional<String> cachedName(GApi api, String id, String account) {
        if (id == null) return java.util.Optional.empty();
        var hit = cache.get(account + "|" + api.alias + "|" + id);
        if (hit == null || hit.named().status() != Status.RESOLVED) return java.util.Optional.empty();
        if (System.currentTimeMillis() - hit.at() >= POSITIVE_MS) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(hit.named().name());
    }

    /** Dropped on lock, along with everything else derived from a credential. */
    public void clear() {
        cache.clear();
    }

    private static Named lookUp(GApi api, String id, String account, String bearer) {
        // A mailbox is not an id and needs no call: the address IS its human name.
        if (api == GApi.GMAIL) return Named.resolved(account, "mailbox");

        var url = metadataUrl(api, id);
        if (url == null) return Named.unknown("the wallet has no name lookup for " + api.alias);
        try {
            var res = LOOKUP.send(HttpRequest.newBuilder(URI.create(url))
                            .header("Authorization", "Bearer " + bearer)
                            .timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return switch (res.statusCode()) {
                case 200 -> read(api, res.body());
                case 404 -> Named.unreachable(account + " cannot see it at all (Google: not found)");
                case 403 -> Named.unreachable(account + " is not allowed to see it (Google: forbidden)");
                default -> Named.unknown("Google answered " + res.statusCode() + " to the name lookup");
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Named.unknown("interrupted while asking Google for the name");
        } catch (Exception e) {
            return Named.unknown("the name lookup failed: " + e);
        }
    }

    /**
     * Metadata only, field-masked, so this can never become a way to read a document early.
     *
     * <p>{@code supportsAllDrives} on the Drive call is load-bearing: without it every shared-drive file
     * answers 404, and the wallet would refuse perfectly ordinary work while reporting it as missing.
     */
    private static String metadataUrl(GApi api, String id) {
        var e = URLEncoder.encode(id, StandardCharsets.UTF_8);
        return switch (api) {
            case SHEETS -> api.upstream + "v4/spreadsheets/" + e + "?fields=properties.title";
            case DOCS -> api.upstream + "v1/documents/" + e + "?fields=title";
            case SLIDES -> api.upstream + "v1/presentations/" + e + "?fields=title";
            // The export host serves no metadata of its own, and the id is a presentation id, so the
            // name comes from the Slides API. Without this the dialog for a render would show a bare id.
            case SLIDES_EXPORT -> GApi.SLIDES.upstream + "v1/presentations/" + e + "?fields=title";
            case DOCS_EXPORT -> GApi.DRIVE.upstream + "drive/v3/files/" + e + "?fields=name&supportsAllDrives=true";
            case DRIVE -> api.upstream + "drive/v3/files/" + e
                    + "?fields=name,mimeType,trashed,owners(emailAddress)&supportsAllDrives=true";
            case CALENDAR -> calendarUrl(id);
            default -> null;
        };
    }

    /**
     * The one id here that is sometimes two: {@code "<calendarId>/<eventId>"} for an event, a bare
     * calendar id otherwise — see {@link uskoag.wallet.daemon.CalendarRules}. Each half is encoded on
     * its own, never the compound string as a whole, or the "/" that separates them would come back as
     * {@code %2F} and point nowhere.
     */
    private static String calendarUrl(String id) {
        var slash = id.indexOf('/');
        if (slash < 0) {
            return GApi.CALENDAR.upstream + "calendar/v3/calendars/"
                    + URLEncoder.encode(id, StandardCharsets.UTF_8) + "?fields=summary";
        }
        var calId = URLEncoder.encode(id.substring(0, slash), StandardCharsets.UTF_8);
        var eventId = URLEncoder.encode(id.substring(slash + 1), StandardCharsets.UTF_8);
        return GApi.CALENDAR.upstream + "calendar/v3/calendars/" + calId + "/events/" + eventId + "?fields=summary";
    }

    private static Named read(GApi api, String body) {
        JsonObject o;
        try {
            o = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            return Named.unknown("Google's answer to the name lookup did not parse");
        }
        return switch (api) {
            case SHEETS -> {
                var props = o.getAsJsonObject("properties");
                yield named(props == null ? null : str(props, "title"), "spreadsheet");
            }
            case DOCS -> named(str(o, "title"), "document");
            case SLIDES, SLIDES_EXPORT -> named(str(o, "title"), "presentation");
            case DOCS_EXPORT -> named(str(o, "name"), "document");
            case CALENDAR -> named(str(o, "summary"), "calendar#event".equals(str(o, "kind")) ? "event" : "calendar");
            case DRIVE -> {
                var kind = kindOf(str(o, "mimeType"));
                var owner = owner(o);
                var trashed = o.has("trashed") && !o.get("trashed").isJsonNull() && o.get("trashed").getAsBoolean();
                yield named(str(o, "name"), kind
                        + (trashed ? ", already in the trash" : "")
                        + (owner == null ? "" : ", owned by " + owner));
            }
            default -> Named.unknown("no name lookup for " + api.alias);
        };
    }

    private static Named named(String title, String kind) {
        return title == null || title.isBlank()
                ? Named.unknown("Google named no title for it")
                : Named.resolved(title, kind);
    }

    /**
     * Whether it is a folder is worth more than the mime string it came from: "delete a folder" and
     * "delete a file" are different questions and the dialog should not make you decode one into
     * the other.
     */
    private static String kindOf(String mime) {
        if (mime == null) return "file";
        return switch (mime) {
            case "application/vnd.google-apps.folder" -> "FOLDER";
            case "application/vnd.google-apps.spreadsheet" -> "spreadsheet";
            case "application/vnd.google-apps.document" -> "document";
            case "application/vnd.google-apps.presentation" -> "presentation";
            case "application/vnd.google-apps.form" -> "form";
            case "application/vnd.google-apps.shortcut" -> "shortcut";
            case "application/pdf" -> "PDF";
            default -> mime.startsWith("image/") ? "image"
                    : mime.startsWith("video/") ? "video"
                    : mime.startsWith("audio/") ? "audio" : "file";
        };
    }

    private static String owner(JsonObject o) {
        var owners = o.getAsJsonArray("owners");
        if (owners == null || owners.isEmpty()) return null;
        var first = owners.get(0);
        return first.isJsonObject() ? str(first.getAsJsonObject(), "emailAddress") : null;
    }

    private static String str(JsonObject o, String field) {
        var v = o.get(field);
        return v == null || v.isJsonNull() ? null : v.getAsString();
    }
}

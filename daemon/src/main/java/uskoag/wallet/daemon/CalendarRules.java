package uskoag.wallet.daemon;

import uskoag.wallet.wire.ResourceRef;

import java.util.List;
import java.util.Set;

/**
 * Calendar is path-addressable, like Drive, but one layer deeper: an event needs both its calendar and
 * its own id to be looked up or approved individually, and {@link ResourceRef#id()} is a single string.
 * The compound id {@code "<calendarId>/<eventId>"} carries both through that one slot — {@link
 * ResourceNames} and {@link PolicyEngine}'s exact-match rules need nothing more than an opaque key they
 * can look up and compare, so a "/" inside it costs nothing.
 *
 * <p>Two traps worth stating up front, because the API's own naming invites confusing them.
 * {@code calendars/{id}} DELETE destroys the calendar and every event on it — irreversible, no trash.
 * {@code users/me/calendarList/{id}} DELETE only unsubscribes it from this account's own list — the
 * calendar itself, and everyone else's access to it, is untouched. Reading either as "delete calendar"
 * would either alarm someone over a harmless list edit or wave through a real deletion as one.
 *
 * <p>{@code acl.insert}/{@code acl.update} (sharing) sit in the destructive tier for the same reason
 * {@link DriveRules#permissions} does: granting visibility into a calendar is the standing-risk
 * direction, and un-sharing later does not retract what was already seen.
 */
public final class CalendarRules {

    private static final String PREFIX = "/calendar/v3/";
    private static final Set<String> EVENT_CREATE_ALIASES = Set.of("quickAdd", "import", "watch");

    private CalendarRules() {
    }

    public static Classification classify(RequestFacts f) {
        var path = f.path();
        var rest = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
        var seg = rest.isEmpty() ? List.<String>of() : List.of(rest.split("/"));

        if (seg.isEmpty()) return fallback(f);
        return switch (seg.get(0)) {
            case "calendars" -> calendars(f, seg);
            case "users" -> calendarList(f, seg);
            case "freeBusy" -> Classification.read("check free/busy", ResourceRef.browse("calendar"));
            default -> fallback(f);
        };
    }

    private static Classification fallback(RequestFacts f) {
        var res = ResourceRef.browse("calendar");
        if (f.reads()) return Classification.read("read", res);
        if (f.is("DELETE")) return Classification.destructive("delete", res, 1);
        return Classification.mutate(f.method().toLowerCase(), res);
    }

    private static Classification calendars(RequestFacts f, List<String> seg) {
        if (seg.size() == 1) return Classification.mutate("create a new calendar", ResourceRef.browse("calendar"));

        var calendarId = seg.get(1);
        var cal = new ResourceRef("calendar", calendarId, null);
        if (seg.size() == 2) {
            if (f.reads()) return Classification.read("read calendar settings", cal);
            if (f.is("DELETE")) return Classification.destructive("permanently delete this calendar and every event in it", cal, 1);
            return Classification.mutate("change calendar settings", cal);
        }

        return switch (seg.get(2)) {
            case "clear" -> Classification.destructive("erase every event on this calendar", cal, 1);
            case "acl" -> acl(f, seg, cal);
            case "events" -> events(f, seg, cal, calendarId);
            default -> f.reads() ? Classification.read("read", cal) : Classification.mutate(f.method().toLowerCase(), cal);
        };
    }

    private static Classification acl(RequestFacts f, List<String> seg, ResourceRef cal) {
        if (seg.size() == 3) {
            if (f.reads()) return Classification.read("list who this calendar is shared with", cal);
            return Classification.destructive("SHARE this calendar with someone", cal, 1);
        }
        if (f.reads()) return Classification.read("read a sharing rule", cal);
        if (f.is("DELETE")) return Classification.destructive("remove someone's access to this calendar", cal, 1);
        return Classification.destructive("change someone's access to this calendar", cal, 1);
    }

    private static Classification events(RequestFacts f, List<String> seg, ResourceRef cal, String calendarId) {
        if (seg.size() == 3) {
            if (f.reads()) return Classification.read("list events", cal);
            return withAttendeesNote("create an event", cal, f);
        }

        var third = seg.get(3);
        if (EVENT_CREATE_ALIASES.contains(third)) {
            return Classification.mutate("quickAdd".equals(third) ? "quick-add an event from text" : "create an event", cal);
        }

        var ev = new ResourceRef("calendar", calendarId + "/" + third, null);
        if (seg.size() == 4) {
            if (f.reads()) return Classification.read("read an event", ev);
            if (f.is("DELETE")) return Classification.destructive("permanently delete this event", ev, 1);
            return withAttendeesNote("modify an event", ev, f);
        }

        var fourth = seg.get(4);
        if ("instances".equals(fourth)) return Classification.read("list this recurring event's instances", ev);
        if ("move".equals(fourth)) return Classification.mutate("move this event to another calendar", ev);
        return f.reads() ? Classification.read("read", ev) : Classification.mutate(f.method().toLowerCase(), ev);
    }

    /** Google sends the actual invite email off an {@code attendees[]} array — worth naming, not folded into a generic "modify". */
    private static Classification withAttendeesNote(String verb, ResourceRef res, RequestFacts f) {
        var invited = BatchRequests.arraySizeOrZero(f.body(), "attendees");
        return Classification.mutate(invited > 0 ? verb + " and invite " + invited + " attendee(s)" : verb, res);
    }

    private static Classification calendarList(RequestFacts f, List<String> seg) {
        if (seg.size() < 3 || !"me".equals(seg.get(1)) || !"calendarList".equals(seg.get(2))) return fallback(f);

        if (seg.size() == 3) {
            if (f.reads()) return Classification.read("list your calendars", ResourceRef.browse("calendar"));
            return Classification.mutate("add a calendar to your list", ResourceRef.browse("calendar"));
        }

        var cal = new ResourceRef("calendar", seg.get(3), null);
        if (f.reads()) return Classification.read("read your settings for this calendar", cal);
        if (f.is("DELETE")) return Classification.mutate("remove this calendar from your list (does not delete it)", cal);
        return Classification.mutate("change your settings for this calendar (colour, notifications)", cal);
    }
}

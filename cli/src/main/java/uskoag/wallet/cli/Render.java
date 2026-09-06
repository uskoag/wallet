package uskoag.wallet.cli;

import uskoag.wallet.wire.Span;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Column arithmetic for a listing where one item is one line.
 *
 * <p>That property is the entire point and it is fragile: a row that wraps cannot be scanned, and a column
 * that shifts by five characters on the one DESTRUCTIVE rule in the list is how the widest permission
 * becomes the hardest to see. So every column is padded to a fixed width, including the ones that rarely
 * need it.
 */
public final class Render {

    static final DateTimeFormatter
            DAY = DateTimeFormatter.ofPattern("MM-dd HH:mm"),
            CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Render() {
    }

    /** Truncated with an ellipsis rather than wrapped, because a wrapped table is not a table. */
    static String cut(String s, int width) {
        var v = s == null || "null".equals(s) ? "" : s;
        return v.length() <= width ? v : v.substring(0, Math.max(0, width - 1)) + "…";
    }

    static String pad(String s, int width) {
        var v = cut(s, width);
        return v + " ".repeat(width - v.length());
    }

    static String local(long epochMillis, DateTimeFormatter how) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(how);
    }

    /** Local time, not an instant: this is read by a person deciding whether a permission still matters. */
    static String expiry(long expiresAt) {
        return expiresAt <= 0 ? "no expiry" : "until " + local(expiresAt, DAY);
    }

    /**
     * The same expiry as a width-4 code — {@code 40m}, {@code 22h}, {@code 6d}.
     *
     * <p>Carried next to the absolute time rather than instead of it. The absolute answers "when", which is
     * what someone comparing two rules needs; this answers "how long have I got", which is what someone
     * deciding whether to extend needs. Spelling the second one out with {@link Span#describe} would cost
     * twelve characters a row to say what four say here.
     */
    static String remaining(long expiresAt, long now) {
        if (expiresAt <= 0) return "";
        var minutes = (expiresAt - now) / 60_000;
        if (minutes <= 0) return "";
        if (minutes < 60) return minutes + "m";
        return minutes < 60 * 48 ? (minutes / 60) + "h" : (minutes / (60 * 24)) + "d";
    }

    /** The local part of an email. An audit listing is one account's, most of the time. */
    static String user(String email) {
        if (email == null || email.isBlank()) return "*";
        var at = email.indexOf('@');
        return at < 0 ? email : email.substring(0, at);
    }

    /** The last segment of a path — "which project", the only question a directory column answers. */
    static String leaf(String path) {
        if (path == null || path.isBlank()) return "";
        var norm = path.replace('\\', '/');
        while (norm.endsWith("/")) norm = norm.substring(0, norm.length() - 1);
        return norm.substring(norm.lastIndexOf('/') + 1);
    }

    static String plural(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}

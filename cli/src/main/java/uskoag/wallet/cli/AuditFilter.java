package uskoag.wallet.cli;

import uskoag.wallet.wire.Span;
import uskoag.wallet.wire.Tier;

import java.util.List;

/**
 * The query half of {@code audit}. "What got refused, in the last two hours, from this project" is the
 * question this log is opened for, and until now the only way to ask it was to print a hundred rows of JSON
 * and read them.
 */
public final class AuditFilter {

    private static final String[] FLAGS =
            {"verdict", "deny", "tool", "account", "api", "tier", "target", "dir", "since"};

    private AuditFilter() {
    }

    /** Whether the caller narrowed anything, which decides how far back {@link AuditList} reads. */
    static boolean active(Args a) {
        for (var f : FLAGS) if (a.flags.containsKey(f)) return true;
        return false;
    }

    static List<Row> select(List<Row> rows, Args a) {
        var verdict = a.has("deny") ? "DENY" : a.get("verdict", null);
        var tier = tier(a);
        var since = since(a);
        return rows.stream()
                .filter(r -> r.is("verdict", verdict))
                .filter(r -> tier == null || atLeast(r.str("tier"), tier))
                .filter(r -> r.is("api", a.get("api", null)))
                .filter(r -> r.contains("tool", a.get("tool", null)))
                .filter(r -> r.contains("account", a.get("account", null)))
                .filter(r -> r.contains("target", a.get("target", null)))
                .filter(r -> r.contains("dir", a.get("dir", null)))
                .filter(r -> since <= 0 || r.num("at") >= since)
                .toList();
    }

    private static Tier tier(Args a) {
        var asked = a.get("tier", null);
        var tier = PolicyCommands.tier(asked);
        if (asked != null && tier == null) {
            System.err.println("--tier '" + asked + "' is not a tier. Use read, write or destructive.");
            System.exit(2);
        }
        return tier;
    }

    /** The stored value is a string, and a row written by a future tier this build does not know is kept. */
    private static boolean atLeast(String stored, Tier wanted) {
        try {
            return Tier.valueOf(stored).atLeast(wanted);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * {@code --since 2h}, through {@link Span#parse} — which already reads {@code 45m 12h 10d 3w 6mo} and
     * already returns null rather than guessing. Refused rather than guessed for the same reason it is
     * elsewhere: reading an unparseable span as "everything" would silently answer a different question
     * from the one asked, and here the wrong answer looks exactly like a clean bill of health.
     */
    private static long since(Args a) {
        var asked = a.get("since", null);
        if (asked == null) return 0;
        var minutes = Span.parse(asked);
        if (minutes == null) {
            System.err.println("--since '" + asked + "' is not a span. Try 45m, 12h, 10d, 3w, 6mo.");
            System.exit(2);
        }
        return minutes == 0 ? 0 : System.currentTimeMillis() - minutes * 60_000L;
    }
}

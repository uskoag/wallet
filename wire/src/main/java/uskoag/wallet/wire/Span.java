package uskoag.wallet.wire;

/**
 * How long a permission stands, in the units a person actually thinks in.
 *
 * <p>Minutes are the stored unit because {@link PolicyRule} expires in milliseconds and an {@code int}
 * of minutes reaches four thousand years without overflowing. Nobody types minutes, though. The presets
 * are the spans that actually come up — an hour for one job, a day for one sitting, a week or a month
 * for a standing arrangement — because a dialog offering a free-text box and nothing else makes the
 * common case the slowest one.
 *
 * <p>{@code minutes == 0} means no expiry, matching {@code PolicyRule.expiresAt}. Anything past six
 * months is offered as {@link #FOREVER} rather than dressed up as a number, since a grant outliving any
 * memory of granting it is not bounded in a way worth claiming.
 */
public enum Span {

    HOUR("1 hour", 60),
    DAY("1 day", 60 * 24),
    WEEK("1 week", 60 * 24 * 7),
    MONTH("1 month", 60 * 24 * 30),
    QUARTER("3 months", 60 * 24 * 91),
    HALF("6 months", 60 * 24 * 182),
    FOREVER("Forever", 0);

    public final String label;
    public final int minutes;

    Span(String label, int minutes) {
        this.label = label;
        this.minutes = minutes;
    }

    /**
     * Reads a hand-typed span: {@code 45m}, {@code 12h}, {@code 3d}, {@code 2w}, {@code 6mo},
     * {@code 1y}, or a bare number meaning days. {@code forever} and {@code never} mean no expiry.
     *
     * @return minutes, 0 for no expiry, or null when the text is not a span — the caller decides what
     *         to do about that, because silently reading unparseable input as "forever" would turn a
     *         typo into a permanent grant
     */
    public static Integer parse(String text) {
        if (text == null) return null;
        var s = text.trim().toLowerCase().replace(" ", "");
        if (s.isEmpty()) return null;
        if (s.equals("forever") || s.equals("never") || s.equals("always")) return 0;

        // Longest suffix first: "mo" has to be tested before "m", or six months becomes six minutes.
        var units = new String[]{"mo", "min", "m", "h", "hr", "d", "w", "y"};
        var perUnit = new int[]{60 * 24 * 30, 1, 1, 60, 60, 60 * 24, 60 * 24 * 7, 60 * 24 * 365};
        for (var i = 0; i < units.length; i++) {
            if (!s.endsWith(units[i])) continue;
            var n = number(s.substring(0, s.length() - units[i].length()));
            return n == null ? null : (int) Math.min(Integer.MAX_VALUE, (long) n * perUnit[i]);
        }
        var bare = number(s);
        return bare == null ? null : (int) Math.min(Integer.MAX_VALUE, (long) bare * 60 * 24);
    }

    private static Integer number(String s) {
        if (s.isEmpty()) return null;
        try {
            var n = Integer.parseInt(s);
            return n <= 0 ? null : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The span in words, for a dialog, a rule listing or an audit line. */
    public static String describe(int minutes) {
        if (minutes <= 0) return "no expiry";
        for (var s : values()) if (s.minutes == minutes) return s.label;
        if (minutes < 60) return minutes + " min";
        if (minutes < 60 * 24) return round(minutes, 60) + " hours";
        if (minutes < 60 * 24 * 60) return round(minutes, 60 * 24) + " days";
        return round(minutes, 60 * 24 * 30) + " months";
    }

    private static String round(int minutes, int per) {
        var whole = minutes / per;
        return minutes % per == 0 ? String.valueOf(whole) : String.valueOf(Math.round(minutes / (double) per));
    }
}

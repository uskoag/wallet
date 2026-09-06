package uskoag.wallet.cli;

import uskoag.wallet.wire.PolicyRule;

import java.util.ArrayList;
import java.util.List;

import static uskoag.wallet.cli.Render.expiry;
import static uskoag.wallet.cli.Render.pad;
import static uskoag.wallet.cli.Render.remaining;
import static uskoag.wallet.cli.Render.user;

/** One collapsed scope as one line: id, tier, who, where, which document, how many, how long. */
public final class RuleRow {

    private static final int
            ID = 8,
            TIER = 11,
            ACCOUNT = 10,
            API = 6,
            DOC = 30,
            COUNT = 4,
            WHEN = 17;

    private RuleRow() {
    }

    static String of(List<PolicyRule> group, long now, boolean showAccount) {
        var r = group.getFirst();
        var cells = new ArrayList<String>();
        cells.add(pad(r.id, ID));
        cells.add(pad(String.valueOf(r.tier), TIER));
        if (showAccount) cells.add(pad(user(r.account), ACCOUNT));
        cells.add(pad(r.api == null ? "*" : r.api, API));
        cells.add(pad(document(r), DOC));
        cells.add(pad(group.size() > 1 ? "x" + group.size() : "", COUNT));
        cells.add(pad(expiry(r.expiresAt), WHEN));
        cells.add(pad(remaining(r.expiresAt, now), 4));
        return ("  " + String.join("  ", cells) + ops(r) + death(r, now)).stripTrailing();
    }

    /**
     * The document, spelled out rather than left as an id.
     *
     * <p>A blanket rule says EVERYTHING for the same reason {@code PolicyRule.describe} does: four asterisks
     * are easy to skim past as "unset" when they mean the opposite. The label is preferred over the id
     * because a listing exists to be recognised, and the id is the fallback rather than an addition —
     * carrying both would cost the column its width for a string nobody reads off the screen anyway.
     */
    private static String document(PolicyRule r) {
        if (r.blanket()) return "EVERYTHING";
        if (r.label != null && !r.label.isBlank()) return r.label;
        return r.resource == null ? "*" : r.resource;
    }

    /** Silence means unlimited, which is already how {@code describe()} reads. */
    private static String ops(PolicyRule r) {
        return r.opsBudget < 0 ? "" : "  " + (r.opsBudget - r.opsUsed) + " of " + r.opsBudget + " ops";
    }

    /**
     * Which of the two deaths this was, on the rules {@code --all} reveals.
     *
     * <p>They call for different remedies and the distinction is invisible in the JSON: an expired rule
     * wants {@code policy extend}, an exhausted one has hit its operation budget and wants a fresh grant.
     * {@code live()} covers both, so a listing that only said "not live" would send half its readers to the
     * wrong verb.
     */
    private static String death(PolicyRule r, long now) {
        if (r.live(now)) return "";
        return r.expired(now) ? "  EXPIRED" : "  SPENT";
    }
}

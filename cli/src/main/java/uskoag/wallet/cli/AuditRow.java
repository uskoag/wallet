package uskoag.wallet.cli;

import java.util.ArrayList;

import static uskoag.wallet.cli.Render.CLOCK;
import static uskoag.wallet.cli.Render.leaf;
import static uskoag.wallet.cli.Render.local;
import static uskoag.wallet.cli.Render.pad;
import static uskoag.wallet.cli.Render.user;

/** One recorded request as one line, with the command and full directory held back for {@code --expand}. */
public final class AuditRow {

    private static final int
            VERDICT = 6,
            TIER = 11,
            TOOL = 10,
            ACCOUNT = 10,
            OPERATION = 13,
            COUNT = 4,
            TARGET = 20,
            // Wide enough to tell two project folders apart, which is the only reason the column exists. At
            // ten it truncated every one of them to the same "uandocume…" and answered nothing.
            DIR = 16;

    private AuditRow() {
    }

    static String of(Row r, boolean showAccount) {
        var cells = new ArrayList<String>();
        cells.add(local(r.num("at"), CLOCK));
        cells.add(pad(r.str("verdict"), VERDICT));
        cells.add(pad(r.str("tier"), TIER));
        cells.add(pad(r.str("tool"), TOOL));
        if (showAccount) cells.add(pad(user(r.str("account")), ACCOUNT));
        cells.add(pad(r.str("operation"), OPERATION));
        // The item count, which is what separates one edit from a batch of two hundred and is the first
        // thing anyone looks for when an operation budget ran out sooner than expected.
        cells.add(pad(r.num("count") > 1 ? "x" + r.num("count") : "", COUNT));
        cells.add(pad(r.str("target"), TARGET));
        cells.add(pad(leaf(r.str("dir")), DIR));
        return ("  " + String.join("  ", cells)).stripTrailing();
    }

    /**
     * The command and the directory it ran in, in full.
     *
     * <p>These are the two facts PRP 02 exists to have captured, and they are the longest strings in the
     * record — a command line runs to a hundred characters and the whole point of it is that none of it is
     * elided. So they get their own indented line, and only when asked for: on by default they would have
     * made every row wrap, which is the defect this listing was written to fix.
     */
    static String detail(Row r) {
        var dir = r.str("dir");
        var peer = r.str("peer");
        if (dir.isEmpty() && peer.isEmpty()) return null;
        return "            " + (dir.isEmpty() ? "(directory not recorded)" : dir)
                + (peer.isEmpty() ? "" : "  $ " + peer);
    }
}

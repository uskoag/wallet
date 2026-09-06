package uskoag.wallet.wire;

import java.util.List;

/**
 * What one sweep found, as the CLI and the tray see it.
 *
 * @param checkedAt when this sweep ran; 0 from a listing that reports stored state rather than a run
 * @param rows      one per credential, in the order they were checked
 */
public record HealthReport(long checkedAt, List<HealthRow> rows) {

    public List<HealthRow> bad() {
        return rows == null ? List.of() : rows.stream().filter(r -> r.health().bad()).toList();
    }

    public boolean allWell() {
        return bad().isEmpty();
    }

    /** The one line the tray balloon and the Accounts banner both need. */
    public String headline() {
        var broken = bad();
        if (broken.isEmpty()) {
            var n = rows == null ? 0 : rows.size();
            return n + " credential(s) checked, all healthy.";
        }
        var reauth = broken.stream().filter(r -> r.health().needsReauth).count();
        return broken.size() + " credential(s) need attention: "
                + broken.stream().map(r -> r.account() + " / " + r.group()).distinct()
                .reduce((a, b) -> a + ", " + b).orElse("")
                + (reauth > 0 ? ". Open the wallet, Accounts tab, and press R on each." : ".");
    }
}

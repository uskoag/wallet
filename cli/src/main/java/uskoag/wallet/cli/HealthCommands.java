package uskoag.wallet.cli;

import uskoag.wallet.wire.Asks;
import uskoag.wallet.wire.HealthReport;
import uskoag.wallet.wire.HealthRow;
import uskoag.wallet.wire.Json;
import uskoag.wallet.wire.WalletClient;

import java.util.Map;

/**
 * {@code uskoag-walletcli health} — the daily readonly credential check, from a terminal.
 *
 * <p>Here because the moment anyone wants this answer is the moment a tool has just failed with something
 * unhelpful, and the terminal is where they are standing. Also because a state only a window can display
 * cannot be put in a script, and this one belongs in the morning's checks.
 */
public final class HealthCommands {

    private HealthCommands() {
    }

    public static int run(WalletClient client, Args a) throws Exception {
        var sub = a.at(1) == null ? "list" : a.at(1);
        return switch (sub) {
            case "list" -> list(client.callRaw("health.list", Map.of()), a);
            case "check" -> list(client.callRaw("health.check", new Asks.CheckHealth(
                    a.get("email", a.at(2)), a.get("group", null), true)), a);
            case "due" -> WalletCli.out(client.callRaw("health.due", Map.of()));
            case "reauth" -> WalletCli.out(client.callRaw("health.reauth", new Asks.Reauth(
                    a.get("email", a.at(2)), a.get("group", null), a.num("port", 8888))));
            default -> {
                System.err.println("usage: uskoag-walletcli health [list] | check [<email>] [--group <g>]"
                        + " | reauth <email> [--group <g>] | due       [--json] [--tsv]");
                yield 1;
            }
        };
    }

    /**
     * Human by default, {@code --json} passthrough, {@code --tsv} for a sheet — the convention every other
     * listing in this tool follows.
     *
     * <p>Exit 1 when anything needs attention, so a scheduled task can act on it. That is the whole reason
     * to have this verb at all rather than reading the window.
     */
    private static int list(String raw, Args a) {
        if (a.has("json")) return WalletCli.out(raw);
        // A refusal rather than a listing — locked, most often. Said as the sentence the wallet wrote,
        // on stderr so a pipeline reading stdout gets nothing rather than a line of JSON it will try to
        // parse as a row.
        var report = Json.to(raw, HealthReport.class);
        if (report == null || report.rows() == null) {
            var done = Json.to(raw, Asks.Done.class);
            System.err.println(done != null && done.message() != null ? done.message() : raw);
            return 1;
        }
        if (a.has("tsv")) {
            System.out.println(String.join("\t", "account", "group", "health", "note",
                    "checkedEpochMs", "lastHealthyEpochMs", "staleSinceEpochMs", "lifeDays"));
            report.rows().forEach(r -> System.out.println(String.join("\t", r.account(), r.group(),
                    r.health().name(), note(r).replace('\t', ' '), String.valueOf(r.checkedAt()),
                    String.valueOf(r.healthyAt()), String.valueOf(r.staleSince()),
                    String.valueOf(r.lifeDays()))));
            return report.allWell() ? 0 : 1;
        }
        for (var r : report.rows()) {
            System.out.printf("%-34s %-14s %-24s %-13s %3dd%n",
                    Render.cut(r.account(), 34), Render.cut(r.group(), 14),
                    Render.cut(r.health().label, 24),
                    r.healthyAt() <= 0 ? "never healthy" : "ok " + Render.local(r.healthyAt(), Render.DAY),
                    r.lifeDays());
            if (r.health() != uskoag.wallet.wire.Health.HEALTHY && !note(r).isBlank()) {
                System.out.println("      " + note(r));
            }
        }
        System.out.println();
        System.out.println(report.headline());
        return report.allWell() ? 0 : 1;
    }

    private static String note(HealthRow r) {
        return r.note() == null ? "" : r.note();
    }
}

package uskoag.wallet.ui;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.TableView;
import uskoag.wallet.daemon.WalletCore;

import java.util.Map;

import static luvjfx.Fx.button;
import static luvjfx.Fx.hbox;
import static luvjfx.Fx.label;
import static luvjfx.Fx.textField;
import static luvjfx.Fx.vbox;

/**
 * What was actually attempted, not merely what was approved — denials included, and the calls that were
 * never prompted because a rule already covered them. That is what makes this useful for spotting a
 * runaway automation rather than replaying your own clicks.
 *
 * <p>A table, because the reason to open this tab is always a comparison: which tool, how often, how many
 * at once, and what got refused. A single formatted line per row cannot be sorted by any of those.
 *
 * <p>No pagination yet, and that is a decision rather than an omission: a few hundred rows sort and scroll
 * instantly, and paging would add controls that earn nothing at this size. The row limit below is the
 * thing to raise first, and it is stated in the tab so a truncated view can never read as a complete one.
 */
public final class AuditPane {

    /** Kept visible in the UI. A silent cap on an audit is indistinguishable from a quiet period. */
    private static final int LIMIT = 500;

    private AuditPane() {
    }

    public static Node build(WalletCore core) {
        var table = new TableView<Map<String, Object>>();
        table.getColumns().addAll(
                Cols.of("When", 130, r -> Cols.shortStamp(r.get("at"))),
                Cols.of("Verdict", 70, r -> Cols.text(r.get("verdict"))),
                Cols.of("Tier", 95, r -> Cols.text(r.get("tier"))),
                Cols.of("Tool", 80, r -> Cols.text(r.get("tool"))),
                Cols.of("Account", 185, r -> Cols.text(r.get("account"))),
                Cols.of("Api", 55, r -> Cols.text(r.get("api"))),
                Cols.of("Operation", 210, r -> Cols.text(r.get("operation"))),
                Cols.of("Target", 260, r -> Cols.text(r.get("target"))),
                Cols.of("N", 45, r -> Cols.text(r.get("count"))),
                Cols.of("Session", 175, r -> Cols.text(r.get("session"))),
                Cols.of("Pid", 65, r -> Cols.text(r.get("pid"))),
                Cols.of("Command", 320, r -> Cols.text(r.get("peer"))));
        Cols.ready(table, "Nothing recorded yet.");

        var status = label("");
        var filter = textField();

        Runnable refresh = () -> {
            var unlocked = core.keyring.unlocked();
            var rows = unlocked
                    ? core.audit.recent(LIMIT)
                    : java.util.List.<Map<String, Object>>of();
            // "Nothing recorded yet." over a locked wallet reads as an audit that lost its history, which
            // is the worst thing this particular table could imply about itself.
            Cols.placeholder(table, unlocked, "Nothing recorded yet.");
            var needle = ((javafx.scene.control.TextField) filter.node).getText();
            var shown = needle == null || needle.isBlank() ? rows : rows.stream()
                    .filter(r -> String.valueOf(r.values()).toLowerCase().contains(needle.toLowerCase()))
                    .toList();
            table.setItems(FXCollections.observableArrayList(shown));
            status.text(!unlocked
                    ? "LOCKED — the audit cannot be read. This is not an empty audit."
                    : shown.size() + " of " + rows.size() + " row(s) shown, newest " + LIMIT
                      + " loaded. Detail columns are encrypted at rest and readable here only while the"
                      + " wallet is unlocked.");
        };

        filter.attr(f -> {
            f.setPromptText("filter — any column");
            f.setPrefColumnCount(24);
            f.textProperty().addListener((o, was, now) -> refresh.run());
        });
        refresh.run();

        var reload = button("Refresh");
        reload.attr(b -> b.setOnAction(e -> refresh.run()));

        var pane = vbox().spacing(8).padding(12).nodes(
                        label("Audit  (last 30 days)").style("-fx-font-weight: bold;"))
                .add(table)
                .nodes(hbox().spacing(6).nodes(filter, reload),
                        status.wrapText(true).style("-fx-font-size: 11px; -fx-text-fill: #666;"));
        return pane.node;
    }
}

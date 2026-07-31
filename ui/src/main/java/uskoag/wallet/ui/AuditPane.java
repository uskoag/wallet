package uskoag.wallet.ui;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ListView;
import uskoag.wallet.daemon.WalletCore;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static luvjfx.Fx.button;
import static luvjfx.Fx.hbox;
import static luvjfx.Fx.label;
import static luvjfx.Fx.listView;
import static luvjfx.Fx.vbox;

/**
 * What was actually attempted, not merely what was approved — denials included, and the calls that were
 * never prompted because a rule already covered them. That is what makes this useful for spotting a
 * runaway automation rather than replaying your own clicks.
 */
public final class AuditPane {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private AuditPane() {
    }

    public static Node build(WalletCore core) {
        var list = listView(String.class);
        var status = label("Detail columns are encrypted at rest and decrypted here while the wallet is unlocked.");

        Runnable refresh = () -> ((ListView<String>) list.node).setItems(FXCollections.observableArrayList(
                core.audit.recent(300).stream().map(AuditPane::line).toList()));
        refresh.run();

        var reload = button("Refresh");
        reload.attr(b -> b.setOnAction(e -> refresh.run()));

        return vbox().spacing(8).padding(12).nodes(
                label("Audit  (last 30 days)").style("-fx-font-weight: bold;"),
                list,
                hbox().spacing(6).nodes(reload),
                status.style("-fx-font-size: 11px; -fx-text-fill: #666;")).node;
    }

    private static String line(java.util.Map<String, Object> row) {
        var at = row.get("at") instanceof Number n ? STAMP.format(Instant.ofEpochMilli(n.longValue())) : "?";
        return at + "  " + pad(String.valueOf(row.get("verdict")), 7)
                + pad(String.valueOf(row.get("tier")), 12)
                + pad(String.valueOf(row.get("tool")), 10)
                + row.get("operation") + "   →   " + row.get("target");
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s.substring(0, width) + " " : s + " ".repeat(width - s.length());
    }
}

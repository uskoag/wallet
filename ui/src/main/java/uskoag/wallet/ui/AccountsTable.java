package uskoag.wallet.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import uskoag.wallet.wire.TokenInfo;

import java.time.Duration;
import java.time.Instant;

/**
 * The account/token tree with real column headers, so "used 1.2K, last 3d ago, restricted" line up down
 * the page instead of being padded into a single string that stops aligning the moment a label is long.
 */
public final class AccountsTable {

    private AccountsTable() {
    }

    public static TreeTableView<Row> build() {
        var table = new TreeTableView<Row>();
        table.setShowRoot(false);
        table.setPrefHeight(300);
        table.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT_COLUMNS);
        table.setPlaceholder(new javafx.scene.control.Label(
                "No accounts yet. Add a client on the Clients tab, then Grant here."));

        table.getColumns().add(text("Account / token", 300, r ->
                r.token() == null ? r.text() : "     " + r.token().label()));
        table.getColumns().add(text("Client", 110, r -> r.kind().equals("account") ? r.client() : ""));
        table.getColumns().add(text("Google rates it", 110, r ->
                r.token() == null ? "" : r.token().tier().label));
        table.getColumns().add(text("Uses", 60, r ->
                r.token() == null ? "" : TokenInfo.count(r.token().useCount())));
        table.getColumns().add(text("Last used", 100, r ->
                r.token() == null ? "" : ago(r.token().lastUsed())));
        table.getColumns().add(text("Order", 55, r ->
                r.token() == null ? "" : String.valueOf(r.token().order())));
        return table;
    }

    private static TreeTableColumn<Row, String> text(String heading, int width,
                                                     java.util.function.Function<Row, String> value) {
        var col = new TreeTableColumn<Row, String>(heading);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue() == null || c.getValue().getValue() == null ? "" : value.apply(c.getValue().getValue())));
        return col;
    }

    /** "never", "3d", "2h" — an epoch millisecond tells you nothing at a glance. */
    static String ago(long when) {
        if (when <= 0) return "never";
        var d = Duration.between(Instant.ofEpochMilli(when), Instant.now());
        if (d.toMinutes() < 1) return "just now";
        if (d.toHours() < 1) return d.toMinutes() + "m ago";
        if (d.toDays() < 1) return d.toHours() + "h ago";
        if (d.toDays() < 90) return d.toDays() + "d ago";
        return d.toDays() / 30 + "mo ago";
    }
}

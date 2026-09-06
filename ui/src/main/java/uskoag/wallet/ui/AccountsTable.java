package uskoag.wallet.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import uskoag.wallet.wire.Health;
import uskoag.wallet.wire.TokenInfo;

import java.time.Duration;
import java.time.Instant;

/**
 * The account/token tree with real column headers, so "used 1.2K, last 3d ago, restricted" line up down
 * the page instead of being padded into a single string that stops aligning the moment a label is long.
 *
 * <p>Health and "last healthy" sit third and fourth, ahead of the usage figures, because they are the two
 * that decide whether anything here works at all. A credential Google has stopped honouring is not a
 * detail to be found at the right-hand edge.
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

        table.getColumns().add(text("Account / token", 230, r ->
                r.token() == null ? r.text() : "     " + r.token().label()));
        table.getColumns().add(text("Client", 80, r -> r.kind().equals("account") ? r.client() : ""));
        table.getColumns().add(health());
        table.getColumns().add(text("Last healthy", 90, r ->
                r.token() == null ? "" : ago(r.token().lastHealthyAt())));
        table.getColumns().add(text("Google rates it", 85, r ->
                r.token() == null ? "" : r.token().tier().label));
        table.getColumns().add(text("Uses", 45, r ->
                r.token() == null ? "" : TokenInfo.count(r.token().useCount())));
        table.getColumns().add(text("Last used", 80, r ->
                r.token() == null ? "" : ago(r.token().lastUsed())));
        table.getColumns().add(text("Order", 40, r ->
                r.token() == null ? "" : String.valueOf(r.token().order())));
        return table;
    }

    /**
     * The verdict, in colour, because the whole value of a daily check is that a failure is noticed without
     * anyone going looking for it, and a plain grey word in a column of plain grey words is something
     * you go looking for.
     */
    private static TreeTableColumn<Row, String> health() {
        var col = new TreeTableColumn<Row, String>("Health");
        col.setPrefWidth(160);
        col.setCellValueFactory(c -> new SimpleStringProperty(
                describe(c.getValue() == null ? null : token(c.getValue().getValue()))));
        col.setCellFactory(c -> new javafx.scene.control.TreeTableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
                var row = getTreeTableRow();
                var state = row == null ? null : token(row.getItem());
                setStyle(state == null ? "" : switch (state.state()) {
                    case STALE, CLIENT_GONE -> "-fx-text-fill: #b71c1c; -fx-font-weight: bold;";
                    case SCOPE_LOST -> "-fx-text-fill: #b71c1c;";
                    case BLOCKED, UNREACHABLE -> "-fx-text-fill: #e65100;";
                    case HEALTHY -> "-fx-text-fill: #1b5e20;";
                    case UNKNOWN -> "-fx-text-fill: #777;";
                });
            }
        });
        return col;
    }

    private static TokenInfo token(Row row) {
        return row == null ? null : row.token();
    }

    /**
     * The verdict plus how long it has been that way, which is the difference between news and history.
     *
     * <p>The brief form, and no advice: "STALE — re-authenticate · 6h ago" does not fit any width this
     * column can be given, and what got cut was the state rather than the advice. The advice is in the
     * banner above the table, in the hover, and in the detail box.
     *
     * <p>"When did it last work" is deliberately left to the next column rather than repeated here, so a
     * healthy row reads simply "healthy" and the age beside it means one thing.
     */
    private static String describe(TokenInfo t) {
        if (t == null) return "";
        var state = t.state();
        return state.brief + (state.bad() && t.staleSince() > 0 ? " · " + ago(t.staleSince()) : "");
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

    private static TreeTableColumn<Row, String> text(String heading, int width,
                                                     java.util.function.Function<Row, String> value) {
        var col = new TreeTableColumn<Row, String>(heading);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue() == null || c.getValue().getValue() == null ? "" : value.apply(c.getValue().getValue())));
        return col;
    }
}

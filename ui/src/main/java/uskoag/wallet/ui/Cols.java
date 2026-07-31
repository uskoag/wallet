package uskoag.wallet.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/**
 * Table columns without the ceremony, and the one layout rule every tab here needs.
 *
 * <p>{@link #fill} exists because a table at its default preferred height leaves the rest of a resized
 * window as dead space, which is the opposite of what a window is resized for. Every table and text area
 * in this app should take the room that is going.
 */
public final class Cols {

    static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    static final DateTimeFormatter SHORT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private Cols() {
    }

    /** A read-only text column pulled out of the row with a lambda, which is all any column here needs. */
    static <T> TableColumn<T, String> of(String title, int width, Function<T, String> value) {
        var col = new TableColumn<T, String>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(value.apply(c.getValue())));
        return col;
    }

    /** Grows in both directions, with no ceiling, so extra window space becomes extra rows. */
    static void fill(Region node) {
        node.setMaxWidth(Double.MAX_VALUE);
        node.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(node, Priority.ALWAYS);
    }

    static <T> void ready(TableView<T> table, String emptyMessage) {
        table.setPlaceholder(new javafx.scene.control.Label(emptyMessage));
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        fill(table);
    }

    static String stamp(long millis) {
        return millis <= 0 ? "" : STAMP.format(Instant.ofEpochMilli(millis));
    }

    static String shortStamp(Object millis) {
        return millis instanceof Number n ? SHORT.format(Instant.ofEpochMilli(n.longValue())) : "";
    }

    static String text(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}

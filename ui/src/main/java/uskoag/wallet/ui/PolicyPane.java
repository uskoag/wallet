package uskoag.wallet.ui;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import uskoag.wallet.daemon.WalletCore;
import uskoag.wallet.wire.PolicyRule;
import uskoag.wallet.wire.Span;

import static luvjfx.Fx.button;
import static luvjfx.Fx.choiceBox;
import static luvjfx.Fx.hbox;
import static luvjfx.Fx.label;
import static luvjfx.Fx.listView;
import static luvjfx.Fx.vbox;

/**
 * The approved list, which is what each tool's own XML allow-list used to be — except one place now
 * decides for every tool, one place expires them, and one place is audited.
 *
 * <p>Extending here needs no dialog, unlike the CLI route to the same operation. The difference is who
 * is asking: a click in the wallet's own window IS the consent, while a request arriving over the socket
 * is a process asking on its own behalf, and a process that could grant itself time would make the
 * expiry decorative.
 */
public final class PolicyPane {

    private PolicyPane() {
    }

    @SuppressWarnings("unchecked")
    public static Node build(WalletCore core) {
        var list = listView(String.class);
        var status = label("");
        var span = choiceBox(Span.class);

        var items = (ListView<String>) list.node;
        var picker = (ChoiceBox<Span>) span.node;
        picker.setItems(FXCollections.observableArrayList(Span.values()));
        picker.getSelectionModel().select(Span.WEEK);

        Runnable refresh = () -> {
            var rules = core.keyring.unlocked() ? core.policy.rules() : java.util.List.<PolicyRule>of();
            items.setItems(FXCollections.observableArrayList(
                    rules.stream().map(r -> r.id + "   " + r.describe()).toList()));
            status.text(rules.size() + " standing rule(s). A rule is only ever created by approving"
                    + " something.   Del revokes   ·   E extends by the chosen span");
        };
        refresh.run();

        var extend = button("Extend selected");
        var revoke = button("Revoke selected  (Del)");
        var clear = button("Revoke all");
        var reload = button("Refresh");

        // The id is the first token of each row, which is why it is printed first.
        java.util.function.Supplier<String> selectedId = () -> {
            var row = items.getSelectionModel().getSelectedItem();
            return row == null ? null : row.substring(0, row.indexOf(' '));
        };

        Runnable doExtend = () -> {
            var id = selectedId.get();
            if (id == null) return;
            try {
                status.text("extended: " + core.policy.extend(id, picker.getValue().minutes));
                refresh.run();
            } catch (Exception ex) {
                status.text(String.valueOf(ex.getMessage()));
            }
        };
        Runnable doRevoke = () -> {
            var id = selectedId.get();
            if (id == null) return;
            try {
                core.policy.revoke(id);
                refresh.run();
            } catch (Exception ex) {
                status.text(String.valueOf(ex.getMessage()));
            }
        };

        extend.attr(b -> b.setOnAction(e -> doExtend.run()));
        revoke.attr(b -> b.setOnAction(e -> doRevoke.run()));
        clear.attr(b -> b.setOnAction(e -> {
            var n = items.getItems().size();
            if (n == 0) return;
            if (!Confirm.ask("Revoke all", "Revoke all " + n + " standing permission(s)?\n\n"
                    + "Every tool goes back to asking on first touch. This does not revoke anything at"
                    + " Google — the accounts keep the consents they were given.",
                    "Revoke all " + n, "Keep them")) {
                return;
            }
            try {
                core.policy.clear();
                refresh.run();
            } catch (Exception ex) {
                status.text(String.valueOf(ex.getMessage()));
            }
        }));
        reload.attr(b -> b.setOnAction(e -> refresh.run()));

        // Single keys throughout, no chords.
        items.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) doRevoke.run();
            if (e.getCode() == KeyCode.E) doExtend.run();
        });

        return vbox().spacing(8).padding(12).nodes(
                label("Standing permissions").style("-fx-font-weight: bold;"),
                list,
                hbox().spacing(6).nodes(label("Extend by:"), span, extend, revoke, clear, reload),
                status.style("-fx-font-size: 11px; -fx-text-fill: #666;")).node;
    }
}

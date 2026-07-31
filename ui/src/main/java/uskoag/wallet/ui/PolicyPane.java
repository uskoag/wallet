package uskoag.wallet.ui;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ListView;
import uskoag.wallet.daemon.WalletCore;
import uskoag.wallet.wire.PolicyRule;

import static luvjfx.Fx.button;
import static luvjfx.Fx.hbox;
import static luvjfx.Fx.label;
import static luvjfx.Fx.listView;
import static luvjfx.Fx.vbox;

/**
 * The approved list, which is what each tool's own XML allow-list used to be — except one place now
 * decides for every tool, one place expires them, and one place is audited.
 */
public final class PolicyPane {

    private PolicyPane() {
    }

    public static Node build(WalletCore core) {
        var list = listView(String.class);
        var status = label("");

        Runnable refresh = () -> {
            var rules = core.keyring.unlocked() ? core.policy.rules() : java.util.List.<PolicyRule>of();
            ((ListView<String>) list.node).setItems(FXCollections.observableArrayList(
                    rules.stream().map(r -> r.id + "   " + r.describe()).toList()));
            status.text(rules.size() + " standing rule(s). A rule is only ever created by approving something.");
        };
        refresh.run();

        var revoke = button("Revoke selected");
        var clear = button("Revoke all");
        var reload = button("Refresh");

        revoke.attr(b -> b.setOnAction(e -> {
            var selected = ((ListView<String>) list.node).getSelectionModel().getSelectedItem();
            if (selected == null) return;
            try {
                core.policy.revoke(selected.substring(0, selected.indexOf(' ')));
                refresh.run();
            } catch (Exception ex) {
                status.text(String.valueOf(ex.getMessage()));
            }
        }));
        clear.attr(b -> b.setOnAction(e -> {
            try {
                core.policy.clear();
                refresh.run();
            } catch (Exception ex) {
                status.text(String.valueOf(ex.getMessage()));
            }
        }));
        reload.attr(b -> b.setOnAction(e -> refresh.run()));

        return vbox().spacing(8).padding(12).nodes(
                label("Standing permissions").style("-fx-font-weight: bold;"),
                list,
                hbox().spacing(6).nodes(revoke, clear, reload),
                status.style("-fx-font-size: 11px; -fx-text-fill: #666;")).node;
    }
}

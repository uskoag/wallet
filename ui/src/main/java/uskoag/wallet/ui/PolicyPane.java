package uskoag.wallet.ui;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import uskoag.wallet.daemon.WalletCore;
import uskoag.wallet.wire.PolicyRule;
import uskoag.wallet.wire.Span;
import uskoag.wallet.wire.Tier;

import static luvjfx.Fx.button;
import static luvjfx.Fx.choiceBox;
import static luvjfx.Fx.hbox;
import static luvjfx.Fx.label;
import static luvjfx.Fx.vbox;

/**
 * The approved list, which is what each tool's own XML allow-list used to be — except one place now
 * decides for every tool, one place expires them, and one place is audited.
 *
 * <p>A table rather than one packed line per rule, because the questions asked of this list are "what is
 * about to lapse" and "why does that tool still have access", and both are sorting problems.
 *
 * <p>Extending here needs no dialog, unlike the CLI route to the same operation. The difference is who is
 * asking: a click in the wallet's own window IS the consent, while a request arriving over the socket is a
 * process asking on its own behalf, and a process that could grant itself time would make expiry
 * decorative.
 */
public final class PolicyPane {

    private PolicyPane() {
    }

    @SuppressWarnings("unchecked")
    public static Node build(WalletCore core) {
        var table = new TableView<PolicyRule>();
        table.getColumns().addAll(
                Cols.of("Tier", 105, r -> badge(r.tier)),
                Cols.of("Document", 230, r -> r.label == null || r.label.isBlank() ? "(unnamed)" : r.label),
                Cols.of("Id", 190, r -> r.resource == null ? "*" : r.resource),
                Cols.of("Account", 190, r -> r.account == null ? "any" : r.account),
                Cols.of("Api", 60, r -> r.api == null ? "any" : r.api),
                Cols.of("Tool", 80, r -> r.profile == null ? "any" : r.profile),
                Cols.of("Approved", 145, r -> Cols.stamp(r.createdAt)),
                Cols.of("Expires", 145, r -> r.expiresAt <= 0 ? "never" : Cols.stamp(r.expiresAt)),
                Cols.of("Ops left", 75, r -> r.opsBudget < 0 ? "unlimited" : (r.opsBudget - r.opsUsed) + ""),
                Cols.of("Bound to", 110, r -> r.session == null ? "any run" : "this run only"),
                Cols.of("Rule", 75, r -> r.id));
        Cols.ready(table, "No standing permissions. A rule is only ever created by approving something.");

        var status = label("");
        var span = choiceBox(Span.class);
        var picker = (ChoiceBox<Span>) span.node;
        // Only spans some tier could actually use. The engine clamps per tier anyway, but offering
        // "6 months" and then silently granting an hour would be a lie told by a dropdown.
        picker.setItems(FXCollections.observableArrayList(
                java.util.Arrays.stream(Span.values()).filter(Tier.READ::allows).toList()));
        picker.getSelectionModel().select(Span.DAY);

        Runnable refresh = () -> {
            var unlocked = core.keyring.unlocked();
            var rules = unlocked ? core.policy.rules() : java.util.List.<PolicyRule>of();
            Cols.placeholder(table, unlocked,
                    "No standing permissions. A rule is only ever created by approving something.");
            table.setItems(FXCollections.observableArrayList(rules));
            if (!unlocked) {
                status.text("LOCKED — the standing rules cannot be read. This is not zero rules.");
                return;
            }
            status.text(rules.size() + " standing rule(s).   Del revokes   ·   E extends by the chosen span."
                    + "   Ceilings, measured from now and not raisable: read "
                    + Span.describe(Tier.READ.maxMinutes) + ", change "
                    + Span.describe(Tier.MUTATE.maxMinutes) + ", irreversible "
                    + Span.describe(Tier.DESTRUCTIVE.maxMinutes) + ".");
        };
        refresh.run();

        var extend = button("Extend selected");
        var revoke = button("Revoke selected  (Del)");
        var clear = button("Revoke all");
        var reload = button("Refresh");

        Runnable doExtend = () -> {
            var picked = table.getSelectionModel().getSelectedItem();
            if (picked == null) return;
            try {
                status.text("extended: " + core.policy.extend(picked.id, picker.getValue().minutes));
                refresh.run();
            } catch (Exception ex) {
                status.text(String.valueOf(ex.getMessage()));
            }
        };
        Runnable doRevoke = () -> {
            var picked = table.getSelectionModel().getSelectedItem();
            if (picked == null) return;
            try {
                core.policy.revoke(picked.id);
                refresh.run();
            } catch (Exception ex) {
                status.text(String.valueOf(ex.getMessage()));
            }
        };

        extend.attr(b -> b.setOnAction(e -> doExtend.run()));
        revoke.attr(b -> b.setOnAction(e -> doRevoke.run()));
        clear.attr(b -> b.setOnAction(e -> {
            var n = table.getItems().size();
            if (n == 0) return;
            if (!Confirm.ask("Revoke all", "Revoke all " + n + " standing permission(s)?\n\n"
                            + "Every tool goes back to asking on first touch. This does not revoke anything"
                            + " at Google — the accounts keep the consents they were given.",
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
        table.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) doRevoke.run();
            if (e.getCode() == KeyCode.E) doExtend.run();
        });

        var pane = vbox().spacing(8).padding(12).nodes(
                        label("Standing permissions").style("-fx-font-weight: bold;"))
                .add(table)
                .nodes(hbox().spacing(6).nodes(label("Extend by:"), span, extend, revoke, clear, reload),
                        status.wrapText(true).style("-fx-font-size: 11px; -fx-text-fill: #666;"));
        return pane.node;
    }

    private static String badge(Tier tier) {
        if (tier == null) return "";
        return switch (tier) {
            case DESTRUCTIVE -> "IRREVERSIBLE";
            case MUTATE -> "CHANGE";
            case READ -> "READ";
        };
    }
}

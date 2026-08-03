package uskoag.wallet.ui;

import javafx.collections.FXCollections;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import uskoag.wallet.wire.Groups;
import uskoag.wallet.wire.ScopeCatalogue;
import uskoag.wallet.wire.ScopeGroup;
import uskoag.wallet.wire.Tier2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static luvjfx.Fx.button;
import static luvjfx.Fx.checkBox;
import static luvjfx.Fx.hbox;
import static luvjfx.Fx.label;
import static luvjfx.Fx.scene;
import static luvjfx.Fx.scrollPane;
import static luvjfx.Fx.textArea;
import static luvjfx.Fx.textField;
import static luvjfx.Fx.vbox;

/**
 * Choosing what to consent to, with the size of the consent screen visible before you trigger it.
 *
 * <p>That footer is the whole point. "It looked scary" is a symptom of being shown a list you did not
 * choose; here you see how many screens will open, how many permissions each will list, and how many
 * Google classes as restricted — and you can uncheck until it is small.
 */
public final class GrantDialog {

    private GrantDialog() {
    }

    /** The chosen groups plus any hand-written scopes, or empty if cancelled. */
    public static Grant ask(String account) {
        var out = new AtomicReference<Grant>(null);
        var stage = new Stage();
        stage.setAlwaysOnTop(true);
        stage.setTitle("Grant access - " + account);
        AppIcon.applyTo(stage);

        var boxes = new ArrayList<CheckBox>();
        var groups = new ArrayList<ScopeGroup>();
        var body = vbox().spacing(4).padding(4);

        for (var heading : Groups.headings()) {
            body.nodes(label(heading).style("-fx-font-weight: bold; -fx-padding: 8 0 2 0;"));
            for (var g : Groups.under(heading)) {
                var box = checkBox(g.label());
                boxes.add((CheckBox) box.node);
                groups.add(g);
                body.nodes(
                        hbox().spacing(8).nodes(box, badge(g.tier())),
                        label(g.detail()).wrapText(true)
                                .style("-fx-font-size: 11px; -fx-text-fill: #666; -fx-padding: 0 0 4 24;"),
                        label(String.join("\n", g.scopes()))
                                .style("-fx-font-size: 10px; -fx-text-fill: #999; -fx-padding: 0 0 6 24;"));
            }
        }

        var custom = textArea().promptText(
                "One scope per line, for anything the groups above do not cover.\n"
                        + "https://www.googleapis.com/auth/calendar.readonly");
        custom.attr(t -> t.setPrefRowCount(3));

        // ---- the catalogue, which is a typing aid and deliberately not a boundary ----------------
        //
        // Picking from it writes into the box below — the same box anyone could type into by hand, and
        // which accepts anything whether it appears in the catalogue or not. That is the whole design:
        // a shipped list of Google scopes is stale within months, and a picker that gated what could be
        // asked for would then be an obstacle with no way past it. So it only saves the typing, and
        // typing a scope URL exactly right from memory is precisely where this went wrong before.
        var search = textField().promptText("search the scope catalogue:  drive read   ·   gmail send   ·   calendar");
        var results = new javafx.scene.control.ListView<ScopeCatalogue.Entry>();
        results.setPrefHeight(150);
        results.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        results.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ScopeCatalogue.Entry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.display());
                setStyle(empty || item == null ? "" : "-fx-font-size: 11px; -fx-text-fill: "
                        + (item.tier() == Tier2.RESTRICTED ? "#b71c1c;"
                           : item.tier() == Tier2.SENSITIVE ? "#e65100;" : "#333;"));
            }
        });
        Runnable filter = () -> results.setItems(FXCollections.observableArrayList(
                ScopeCatalogue.search(((javafx.scene.control.TextField) search.node).getText())));
        filter.run();
        ((javafx.scene.control.TextField) search.node).textProperty()
                .addListener((o, was, is) -> filter.run());

        var add = button("Add selected to the list below");
        Runnable addPicked = () -> {
            var picked = results.getSelectionModel().getSelectedItems();
            if (picked.isEmpty()) return;
            var area = (TextArea) custom.node;
            var have = new java.util.LinkedHashSet<>(lines(area));
            picked.forEach(p -> have.add(p.url()));
            area.setText(String.join("\n", have));
        };
        add.attr(b -> b.setOnAction(e -> addPicked.run()));
        results.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) addPicked.run();
        });
        results.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) addPicked.run();
        });

        body.nodes(
                label("Anything else Google offers").style("-fx-font-weight: bold; -fx-padding: 10 0 2 0;"),
                label("A search over known Google scopes. It is a typing aid, not a limit: this list will"
                        + " go stale because Google adds scopes and we do not, so the box underneath"
                        + " accepts anything at all — whether it appears here or not. Red is what Google"
                        + " calls restricted, amber sensitive.").wrapText(true)
                        .style("-fx-font-size: 11px; -fx-text-fill: #666;"),
                search,
                luvjfx.Fx.fx(results),
                hbox().spacing(8).nodes(add,
                        label("Enter or double-click also adds")
                                .style("-fx-font-size: 11px; -fx-text-fill: #777;")),
                label("Scopes to request").style("-fx-font-weight: bold; -fx-padding: 10 0 2 0;"),
                label("Everything here becomes its own group with its own token and its own expiry."
                        + " Edit freely — hand-typed lines are as valid as picked ones.").wrapText(true)
                        .style("-fx-font-size: 11px; -fx-text-fill: #666;"),
                custom);

        var footer = label("").wrapText(true).style("-fx-font-weight: bold;");
        Runnable recount = () -> footer.text(summarise(boxes, groups, (TextArea) custom.node));
        boxes.forEach(b -> b.selectedProperty().addListener((o, was, is) -> recount.run()));
        ((TextArea) custom.node).textProperty().addListener((o, was, is) -> recount.run());
        recount.run();

        var go = button("Grant").defaultButton(true);
        var cancel = button("Cancel").cancelButton(true);
        go.attr(b -> b.setOnAction(e -> {
            var chosen = new ArrayList<String>();
            for (var i = 0; i < boxes.size(); i++) if (boxes.get(i).isSelected()) chosen.add(groups.get(i).id());
            out.set(new Grant(chosen, lines((TextArea) custom.node)));
            stage.close();
        }));
        cancel.attr(b -> b.setOnAction(e -> stage.close()));

        var root = vbox().spacing(8).padding(14).nodes(
                label("What may " + account + " be used for?").style("-fx-font-size: 15px; -fx-font-weight: bold;"),
                scrollPane().content(body).attr(s -> {
                    s.setFitToWidth(true);
                    s.setPrefHeight(420);
                }),
                footer,
                hbox().spacing(8).nodes(go, cancel),
                label("Space toggles   |   Tab moves   |   Enter grants   |   Esc cancels")
                        .style("-fx-font-size: 11px; -fx-text-fill: #777;"));

        var sc = scene(root.style(Ui.INK), 640, 640);
        Ui.escCloses(sc, stage, null);
        stage.setScene(sc);
        // Sized to the scope list rather than to a guess: this window is as tall as the account's consents
        // make it, so 640 was dead space for a short list and off the screen edge for a long one.
        Ui.fitToContent(stage, root.node, 640, null);
        stage.showAndWait();
        return out.get();
    }

    /** The sentence that makes the consent screen predictable instead of a surprise. */
    private static String summarise(List<CheckBox> boxes, List<ScopeGroup> groups, TextArea custom) {
        var screens = 0;
        var permissions = 0;
        var restricted = 0;
        for (var i = 0; i < boxes.size(); i++) {
            if (!boxes.get(i).isSelected()) continue;
            screens++;
            permissions += groups.get(i).scopes().size();
            if (groups.get(i).tier() == Tier2.RESTRICTED) restricted += groups.get(i).scopes().size();
        }
        var hand = lines(custom);
        if (!hand.isEmpty()) {
            screens++;
            permissions += hand.size();
        }
        if (screens == 0) return "Nothing selected.";
        return screens + " consent screen(s) will open, listing " + permissions + " permission(s) in total"
                + (restricted == 0 ? ", none restricted." : ", of which " + restricted + " restricted.")
                + "  Each group becomes its own token with its own expiry.";
    }

    private static List<String> lines(TextArea area) {
        var text = area.getText();
        if (text == null || text.isBlank()) return List.of();
        return java.util.Arrays.stream(text.split("\\R"))
                .map(String::trim).filter(s -> !s.isEmpty() && s.startsWith("http")).toList();
    }

    private static luvjfx.FxLabel badge(Tier2 tier) {
        return label(tier.label).style("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: white;"
                + " -fx-background-color: " + tier.colour + "; -fx-padding: 1 6 1 6; -fx-background-radius: 3;");
    }
}

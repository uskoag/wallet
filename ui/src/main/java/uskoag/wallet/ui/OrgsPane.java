package uskoag.wallet.ui;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import uskoag.wallet.daemon.AccountVerbs;
import uskoag.wallet.daemon.WalletCore;
import uskoag.wallet.wire.Asks;
import uskoag.wallet.wire.DomainRule;
import uskoag.wallet.wire.OrgInfo;

import java.nio.file.Files;
import java.util.List;

import static luvjfx.Fx.button;
import static luvjfx.Fx.hbox;
import static luvjfx.Fx.label;
import static luvjfx.Fx.textArea;
import static luvjfx.Fx.textField;
import static luvjfx.Fx.vbox;

/**
 * The OAuth clients — one {@code credentials.json} per Cloud project.
 *
 * <p>Its own tab because it is its own job, done once per organisation and then left alone, whereas
 * logging accounts in happens continually. Mixing them made both harder to see.
 *
 * <p>Domains are a list of patterns, not one string. A single client routinely serves several domains,
 * and the wildcard and regex forms exist because no fixed shape survives contact with real orgs.
 */
public final class OrgsPane {

    private OrgsPane() {
    }

    public static Node build(WalletCore core) {
        var verbs = new AccountVerbs(core);
        var table = new TableView<OrgInfo>();
        table.getColumns().addAll(
                Cols.of("Id", 110, OrgInfo::id),
                Cols.of("Organisation", 210, o -> o.label() == null ? "" : o.label()),
                Cols.of("Accounts", 75, o -> String.valueOf(o.accounts())),
                Cols.of("Created under", 200, o -> o.owner() == null ? "(unknown)" : o.owner()),
                Cols.of("Domains answered for", 250, o -> o.domains().isEmpty()
                        ? "(none — accounts must name --org)" : String.join("   ", o.domains())),
                // A pattern that will not compile matches nothing, so it fails silently and looks like a
                // client that simply never claims an account. Saying so beside it is the only way that is
                // ever noticed.
                Cols.of("Pattern problems", 200, OrgsPane::problems),
                Cols.of("Client id", 290, o -> o.clientId() == null ? "" : o.clientId()),
                Cols.of("Added", 145, o -> Cols.stamp(o.addedAt())));
        Cols.ready(table, "No OAuth clients yet. Give one a short id below and upload its credentials.json.");
        var id = textField().promptText("short id, e.g. uskf");
        var labelField = textField().promptText("human name, e.g. USK Foundation");
        var domains = textArea().promptText(
                "One pattern per line:\n"
                        + "uskfoundation.or.ke        exact\n"
                        + "*.kailaasa.org             that domain and any subdomain\n"
                        + "re:^(legal|mail)\\..+\\.org$  regex\n"
                        + "\n"
                        + "Leave empty to require --org when logging an account in.");
        domains.attr(t -> t.setPrefRowCount(6));
        var status = label("");

        Runnable refresh = () -> table.setItems(FXCollections.observableArrayList(core.orgs()));
        refresh.run();

        table.getSelectionModel().selectedItemProperty()
                .addListener((o, was, picked) -> {
                    if (picked == null) return;
                    ((TextField) id.node).setText(picked.id());
                    ((TextField) labelField.node).setText(picked.label() == null ? "" : picked.label());
                    ((TextArea) domains.node).setText(String.join("\n", picked.domains()));
                });

        var upload = button("Add / replace credentials.json  (A)");
        var save = button("Save domains  (S)");
        var remove = button("Remove client  (Del)");
        var reload = button("Refresh");

        upload.attr(b -> b.setOnAction(e -> {
            var orgId = ((TextField) id.node).getText().trim();
            if (orgId.isEmpty()) {
                status.text("Give the client a short id first.");
                return;
            }
            var chooser = new FileChooser();
            chooser.setTitle("credentials.json for " + orgId);
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("credentials.json", "*.json"));
            var file = chooser.showOpenDialog(table.getScene().getWindow());
            if (file == null) return;
            try {
                verbs.addOrg(new Asks.AddOrg(orgId, ((TextField) labelField.node).getText().trim(),
                        Files.readString(file.toPath()), patterns(domains)));
                status.text("Stored. Log accounts in from the Accounts tab.");
                refresh.run();
            } catch (Exception ex) {
                status.text(String.valueOf(ex.getMessage()));
            }
        }));

        save.attr(b -> b.setOnAction(e -> {
            try {
                var reply = verbs.setDomains(new Asks.OrgDomains(
                        ((TextField) id.node).getText().trim(), patterns(domains)));
                status.text(uskoag.wallet.wire.Json.to(reply, Asks.Done.class).message());
                refresh.run();
            } catch (Exception ex) {
                status.text(String.valueOf(ex.getMessage()));
            }
        }));

        remove.attr(b -> b.setOnAction(e -> {
            var orgId = ((TextField) id.node).getText().trim();
            if (!Confirm.ask("Remove OAuth client",
                    "Remove client '" + orgId + "' from the wallet?\n\n"
                            + "Refused if any account still uses it - their tokens would stop refreshing"
                            + " at the next hour boundary with no obvious cause.", "Remove", "Cancel")) return;
            try {
                var reply = verbs.removeOrg(new Asks.OrgRef(orgId));
                status.text(uskoag.wallet.wire.Json.to(reply, Asks.Done.class).message());
                refresh.run();
            } catch (Exception ex) {
                status.text(String.valueOf(ex.getMessage()));
            }
        }));

        reload.attr(b -> b.setOnAction(e -> refresh.run()));

        // Single keys, matching the other tabs. Only fires from the table itself, so typing a domain
        // pattern containing an 'a' or an 's' in the box below cannot set one of these off.
        table.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DELETE -> ((javafx.scene.control.Button) remove.node).fire();
                case A -> ((javafx.scene.control.Button) upload.node).fire();
                case S -> ((javafx.scene.control.Button) save.node).fire();
                default -> { }
            }
        });

        // The same question the Accounts tab answers on hover: everything about the row without having
        // to select it and read it out of four columns.
        table.setRowFactory(t -> {
            var row = new javafx.scene.control.TableRow<OrgInfo>();
            row.itemProperty().addListener((o, was, is) -> row.setTooltip(tooltipFor(is)));
            return row;
        });

        return vbox().spacing(8).padding(12).nodes(
                        label("OAuth clients").style("-fx-font-weight: bold;"),
                        label("One credentials.json per Cloud project. Every account and every tool in the"
                                + " organisation mints its tokens from it. One per org is what an unverified"
                                + " app leaves available, given the hundred-user cap and a Workspace admin's"
                                + " power to refuse a foreign client id.")
                                .wrapText(true).style("-fx-font-size: 11px; -fx-text-fill: #666;"))
                .add(table)
                .nodes(hbox().spacing(6).nodes(label("id"), id, label("name"), labelField),
                        label("Domains this client answers for")
                                .style("-fx-font-size: 11px; -fx-font-weight: bold;"),
                        domains,
                        hbox().spacing(6).nodes(upload, save, remove, reload),
                        label("In the table:   Del removes a client   ·   A adds or replaces its"
                                + " credentials.json   ·   S saves the domains   ·   hover any row for"
                                + " its full detail")
                                .style("-fx-font-size: 11px; -fx-text-fill: #777;"),
                        status.wrapText(true).style("-fx-text-fill: #1b5e20;")).node;
    }

    private static javafx.scene.control.Tooltip tooltipFor(OrgInfo o) {
        if (o == null) return null;
        var bad = problems(o);
        var tip = new javafx.scene.control.Tooltip(
                o.id() + (o.label() == null || o.label().isBlank() ? "" : "   " + o.label()) + "\n"
                        + "client id:      " + (o.clientId() == null ? "(none)" : o.clientId()) + "\n"
                        + "created under:  " + (o.owner() == null ? "(unknown)" : o.owner()) + "\n"
                        + "accounts using: " + o.accounts() + "\n"
                        + "added:          " + Cols.stamp(o.addedAt()) + "\n\n"
                        + "domains answered for:\n  "
                        + (o.domains().isEmpty() ? "(none — accounts must name --org)"
                           : String.join("\n  ", o.domains()))
                        + (bad.isEmpty() ? "" : "\n\nPATTERN PROBLEMS:\n  " + bad));
        tip.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 11px;");
        tip.setShowDelay(javafx.util.Duration.millis(400));
        tip.setShowDuration(javafx.util.Duration.seconds(60));
        tip.setWrapText(false);
        return tip;
    }

    private static List<String> patterns(luvjfx.FxTextArea area) {
        var text = ((TextArea) area.node).getText();
        if (text == null || text.isBlank()) return List.of();
        return java.util.Arrays.stream(text.split("\\R"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }


    private static String problems(OrgInfo o) {
        var bad = o.domains().stream().map(DomainRule::problem)
                .filter(java.util.Objects::nonNull).toList();
        return bad.isEmpty() ? "" : String.join("; ", bad);
    }
}

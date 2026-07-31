package uskoag.wallet.ui;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ListView;
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
import static luvjfx.Fx.listView;
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
        var list = listView(String.class);
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

        Runnable refresh = () -> ((ListView<String>) list.node).setItems(FXCollections.observableArrayList(
                core.orgs().stream().map(OrgsPane::line).toList()));
        refresh.run();

        ((ListView<String>) list.node).getSelectionModel().selectedItemProperty()
                .addListener((o, was, is) -> {
                    if (is == null) return;
                    var picked = core.orgs().stream()
                            .filter(x -> is.startsWith(x.id() + "  ")).findFirst().orElse(null);
                    if (picked == null) return;
                    ((TextField) id.node).setText(picked.id());
                    ((TextField) labelField.node).setText(picked.label() == null ? "" : picked.label());
                    ((TextArea) domains.node).setText(String.join("\n", picked.domains()));
                });

        var upload = button("Add / replace credentials.json");
        var save = button("Save domains");
        var remove = button("Remove client");
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
            var file = chooser.showOpenDialog(list.node.getScene().getWindow());
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

        return vbox().spacing(8).padding(12).nodes(
                label("OAuth clients").style("-fx-font-weight: bold;"),
                label("One credentials.json per Cloud project. Every account and every tool in the"
                        + " organisation mints its tokens from it. One per org is what an unverified app"
                        + " leaves available, given the hundred-user cap and a Workspace admin's power to"
                        + " refuse a foreign client id.")
                        .wrapText(true).style("-fx-font-size: 11px; -fx-text-fill: #666;"),
                list,
                hbox().spacing(6).nodes(label("id"), id, label("name"), labelField),
                label("Domains this client answers for").style("-fx-font-size: 11px; -fx-font-weight: bold;"),
                domains,
                hbox().spacing(6).nodes(upload, save, remove, reload),
                status.wrapText(true).style("-fx-text-fill: #1b5e20;")).node;
    }

    private static List<String> patterns(luvjfx.FxTextArea area) {
        var text = ((TextArea) area.node).getText();
        if (text == null || text.isBlank()) return List.of();
        return java.util.Arrays.stream(text.split("\\R"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String line(OrgInfo o) {
        var bad = o.domains().stream().map(DomainRule::problem).filter(java.util.Objects::nonNull).count();
        return o.id() + "  " + pad(o.label() == null ? "" : o.label(), 26)
                + pad(o.owner() == null ? "owner unknown" : "owner " + o.owner(), 34)
                + pad(o.accounts() + " acct", 8)
                + (o.domains().isEmpty() ? "(no domains - accounts must name --org)"
                : String.join(", ", o.domains()))
                + (bad > 0 ? "   [" + bad + " bad pattern]" : "");
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s.substring(0, width - 1) + " " : s + " ".repeat(width - s.length());
    }
}

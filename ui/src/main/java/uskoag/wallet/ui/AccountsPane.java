package uskoag.wallet.ui;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import uskoag.wallet.daemon.AccountVerbs;
import uskoag.wallet.daemon.TokenVerbs;
import uskoag.wallet.daemon.WalletCore;
import uskoag.wallet.wire.Asks;
import uskoag.wallet.wire.OrgInfo;
import uskoag.wallet.wire.TokenInfo;

import java.util.ArrayList;

import static luvjfx.Fx.button;
import static luvjfx.Fx.choiceBox;
import static luvjfx.Fx.hbox;
import static luvjfx.Fx.label;
import static luvjfx.Fx.textArea;
import static luvjfx.Fx.textField;
import static luvjfx.Fx.vbox;

/**
 * Accounts and the tokens they hold. The OAuth clients themselves live on their own tab, because
 * setting one up happens once per organisation while logging accounts in happens continually.
 */
public final class AccountsPane {

    private static final String INFER = "(infer from domain)";

    private AccountsPane() {
    }

    public static Node build(WalletCore core) {
        var accounts = new AccountVerbs(core);
        var tokens = new TokenVerbs(core);

        var tree = AccountsTable.build();

        var detail = textArea().promptText("Select a token to see exactly which Google permissions it carries.");
        detail.attr(t -> {
            t.setEditable(false);
            t.setPrefRowCount(6);
            t.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 11px;");
        });

        var email = textField().promptText("someone@example.org");
        var client = choiceBox(String.class);
        var status = label("");

        Runnable reloadClients = () -> {
            var ids = new ArrayList<String>();
            ids.add(INFER);
            core.orgs().stream().map(OrgInfo::id).forEach(ids::add);
            ((ChoiceBox<String>) client.node).setItems(FXCollections.observableArrayList(ids));
            if (((ChoiceBox<String>) client.node).getValue() == null) {
                ((ChoiceBox<String>) client.node).setValue(INFER);
            }
        };

        Runnable refresh = () -> {
            reloadClients.run();
            var root = new TreeItem<>(Row.note(null, ""));
            for (var a : core.accounts()) {
                var accNode = new TreeItem<>(Row.account(a.email(), a.org()));
                accNode.setExpanded(true);
                if (!a.hasAnyToken()) accNode.getChildren().add(new TreeItem<>(Row.note(a.email(), "(no tokens - select and press Grant)")));
                for (var t : a.tokens()) accNode.getChildren()
                        .add(new TreeItem<>(Row.token(a.email(), t)));
                root.getChildren().add(accNode);
            }
            if (root.getChildren().isEmpty()) root.getChildren().add(new TreeItem<>(Row.note(null, "No accounts yet.")));
            tree.setRoot(root);
        };
        refresh.run();

        tree.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> {
            if (is == null) return;
            if (is.getValue().account() != null) ((TextField) email.node).setText(is.getValue().account());
            var t = is.getValue().token();
            ((TextArea) detail.node).setText(t == null ? "" :
                    t.label() + "\n" + t.detail()
                            + "\n\nGoogle classes this: " + t.tier().label
                            + "\nUsed " + TokenInfo.count(t.useCount()) + " time(s)"
                            + "\n\nScopes:\n  " + String.join("\n  ", t.scopes()));
        });

        var grant = button("Grant...");
        var remove = button("Remove token");
        var removeUnused = button("Remove unused...");
        var forget = button("Forget account");
        var copy = button("Copy as TSV");
        var reload = button("Refresh");

        grant.attr(b -> b.setOnAction(e -> {
            var who = ((TextField) email.node).getText().trim();
            if (who.isEmpty()) {
                status.text("Type the account email first.");
                return;
            }
            var chosen = GrantDialog.ask(who);
            if (chosen == null || chosen.isEmpty()) return;
            var picked = ((ChoiceBox<String>) client.node).getValue();
            status.text("Consent windows will open, one per group.");
            new Thread(() -> {
                try {
                    accounts.login(new Asks.Login(who, INFER.equals(picked) ? null : picked,
                            chosen.groups(), "custom", chosen.customScopes(), 8888));
                    AuthUrlWindow.dismiss();
                    javafx.application.Platform.runLater(() -> {
                        status.text("Granted.");
                        refresh.run();
                    });
                } catch (Exception ex) {
                    AuthUrlWindow.dismiss();
                    javafx.application.Platform.runLater(() -> status.text(String.valueOf(ex.getMessage())));
                }
            }, "wallet-consent").start();
        }));

        remove.attr(b -> b.setOnAction(e -> {
            var sel = tree.getSelectionModel().getSelectedItem();
            if (sel == null || sel.getValue().token() == null) {
                status.text("Select a token first.");
                return;
            }
            var t = sel.getValue().token();
            if (!Confirm.ask("Remove token", "Remove " + t.label() + " for " + t.account() + "?\n\n"
                    + "This removes it from the wallet only. Google's grant survives - revoke that at\n"
                    + "myaccount.google.com/permissions if that is what you mean.", "Remove", "Cancel")) return;
            act(status, refresh, () -> tokens.remove(new Asks.TokenRef(t.account(), t.group())));
        }));

        removeUnused.attr(b -> b.setOnAction(e -> {
            var stale = core.accounts().stream().flatMap(a -> a.unused().stream()).toList();
            if (stale.isEmpty()) {
                status.text("Every token has been used at least once.");
                return;
            }
            var listing = stale.stream().map(t -> "  " + t.account() + "   " + t.label()).toList();
            if (!Confirm.ask("Remove unused tokens", "Never used:\n\n" + String.join("\n", listing)
                    + "\n\nRemove from the wallet? Google's grants survive.", "Remove", "Cancel")) return;
            act(status, refresh, () -> tokens.removeUnused(new Asks.Unused(0)));
        }));

        forget.attr(b -> b.setOnAction(e -> {
            var who = ((TextField) email.node).getText().trim();
            if (!Confirm.ask("Forget account", "Remove every token for " + who + "?", "Forget", "Cancel")) return;
            act(status, refresh, () -> accounts.forget(new Asks.Forget(who)));
        }));

        copy.attr(b -> b.setOnAction(e -> {
            var sb = new StringBuilder(TokenInfo.tsvHeader()).append('\n');
            core.accounts().forEach(a -> a.tokens().forEach(t -> sb.append(t.tsv()).append('\n')));
            var content = new javafx.scene.input.ClipboardContent();
            content.putString(sb.toString());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            status.text("Inventory copied as TSV.");
        }));

        reload.attr(b -> b.setOnAction(e -> refresh.run()));

        return vbox().spacing(8).padding(12).nodes(
                label("Accounts and tokens").style("-fx-font-weight: bold;"),
                label("One token per scope group, so each expires on its own and an unused mail grant"
                        + " cannot take Sheets down with it. The narrowest token that covers a request is"
                        + " the one used.").wrapText(true).style("-fx-font-size: 11px; -fx-text-fill: #666;"),
                luvjfx.Fx.fx(tree),
                detail,
                hbox().spacing(6).nodes(label("email"), email, label("client"), client),
                hbox().spacing(6).nodes(grant, remove, removeUnused, forget, copy, reload),
                status.wrapText(true).style("-fx-text-fill: #1b5e20;")).node;
    }

    private static void act(luvjfx.FxLabel status, Runnable refresh, Action action) {
        try {
            action.run();
            refresh.run();
        } catch (Exception ex) {
            status.text(String.valueOf(ex.getMessage()));
        }
    }

}

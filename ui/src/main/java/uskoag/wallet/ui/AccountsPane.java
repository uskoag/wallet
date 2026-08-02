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
            // "No accounts yet." was said whenever the list came back empty, and WalletCore.accounts()
            // returns an empty list while the keyring is locked — it cannot read the names, which is not
            // the same fact as there being none. So a locked wallet reported, confidently, that every
            // account had gone. That is alarming in the one place alarm is expensive: it reads as data
            // loss, and the honest answer was three accounts, present, behind a passphrase.
            //
            // Same defect as the empty approval window and the narrowed glob: absence rendered as a clean
            // answer rather than as "not known from here".
            if (root.getChildren().isEmpty()) {
                root.getChildren().add(new TreeItem<>(Row.note(null, core.keyring.unlocked()
                        ? "No accounts yet."
                        : "LOCKED — the accounts cannot be read until the wallet is unlocked."
                          + " Nothing is missing; unlock to see them.")));
            }
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

        var grant = button("Grant...  (G)");
        var remove = button("Remove token  (Del)");
        var up = button("Prefer sooner  (U)");
        var down = button("Prefer later  (D)");
        var removeUnused = button("Remove unused...");
        var unusedDays = textField();
        ((TextField) unusedDays.node).setPrefColumnCount(4);
        ((TextField) unusedDays.node).setText("0");
        var forget = button("Forget account");
        var googlePage = button("Google's permissions page");
        var copy = button("Copy as TSV");
        var reload = button("Refresh");

        Runnable doGrant = () -> {
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
        };
        grant.attr(b -> b.setOnAction(e -> doGrant.run()));

        Runnable doRemove = () -> {
            var sel = tree.getSelectionModel().getSelectedItem();
            if (sel == null || sel.getValue().token() == null) {
                status.text("Select a token first.");
                return;
            }
            var t = sel.getValue().token();
            if (!Confirm.ask("Remove token", "Remove " + t.label() + " for " + t.account() + "?\n\n"
                    + "This removes it from the wallet only. Google's grant survives - revoke that at\n"
                    + "myaccount.google.com/permissions if that is what you mean. The button beside\n"
                    + "this one opens that page.", "Remove", "Cancel")) return;
            act(status, refresh, () -> tokens.remove(new Asks.TokenRef(t.account(), t.group())));
        };
        remove.attr(b -> b.setOnAction(e -> doRemove.run()));

        // Opening Google's own page is the other half of removing a token, and worth its own button
        // rather than a sentence in a dialog nobody can click. Removing here only stops THIS machine
        // using the grant; the grant itself keeps existing until it is revoked there.
        googlePage.attr(b -> b.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(
                        java.net.URI.create("https://myaccount.google.com/permissions"));
                status.text("Opened Google's permissions page. Removing a token here never touches it.");
            } catch (Exception ex) {
                status.text("Could not open a browser. The page is https://myaccount.google.com/permissions");
            }
        }));

        removeUnused.attr(b -> b.setOnAction(e -> {
            int days;
            try {
                days = Integer.parseInt(((TextField) unusedDays.node).getText().trim());
                if (days < 0) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                status.text("The idle threshold must be a whole number of days. 0 means never used at all.");
                return;
            }
            // Asked of the engine rather than recomputed here. The UI used to filter on neverUsed() while
            // the CLI applied a --days window, so the same button and the same verb disagreed about what
            // "unused" meant — and the one that actually deleted was the one nobody had read.
            var stale = tokens.staleTokens(days);
            if (stale.isEmpty()) {
                status.text(days == 0 ? "Every token has been used at least once."
                        : "No token has been idle for " + days + " day(s).");
                return;
            }
            var listing = stale.stream().map(t -> "  " + t.account() + "   " + t.label()
                    + "   (" + AccountsTable.ago(t.lastUsed()) + ")").toList();
            if (!Confirm.ask("Remove unused tokens",
                    (days == 0 ? "Never used:" : "Never used, or idle for over " + days + " day(s):")
                            + "\n\n" + String.join("\n", listing)
                            + "\n\nRemove from the wallet? Google's grants survive, so anything removed"
                            + "\nby mistake comes back with one consent.", "Remove " + stale.size(), "Cancel")) {
                return;
            }
            act(status, refresh, () -> tokens.removeUnused(new Asks.Unused(days)));
        }));

        /**
         * Preference among tokens that all satisfy a request; lower wins.
         *
         * <p>Only ever a tie-break. The picker still prefers a token carrying exactly what a request
         * needs over one that merely subsumes it, so this cannot be used to make a full-control token
         * serve a read — which is the one thing an ordering control must not be able to do.
         */
        java.util.function.IntConsumer move = delta -> {
            var sel = tree.getSelectionModel().getSelectedItem();
            if (sel == null || sel.getValue().token() == null) {
                status.text("Select a token first.");
                return;
            }
            var t = sel.getValue().token();
            var account = core.accounts().stream()
                    .filter(a -> a.email().equalsIgnoreCase(t.account())).findFirst().orElse(null);
            if (account == null) return;
            var order = new ArrayList<>(account.tokens().stream().map(TokenInfo::group).toList());
            var at = order.indexOf(t.group());
            var to = at + delta;
            if (at < 0 || to < 0 || to >= order.size()) {
                status.text(t.label() + " is already " + (delta < 0 ? "first" : "last") + " for this account.");
                return;
            }
            java.util.Collections.swap(order, at, to);
            act(status, refresh, () -> tokens.reorder(new Asks.Reorder(t.account(), order)));
            status.text(t.label() + " now sits at position " + (to + 1) + " for " + t.account() + ".");
        };
        up.attr(b -> b.setOnAction(e -> move.accept(-1)));
        down.attr(b -> b.setOnAction(e -> move.accept(1)));

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

        // Single keys, no chords. Every one of these is also a button — the keys are the fast path for
        // someone already in the table, not the only way to reach the action.
        tree.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DELETE -> doRemove.run();
                case U -> move.accept(-1);
                case D -> move.accept(1);
                case G -> doGrant.run();
                default -> { }
            }
        });

        // The detail box below already lists the scopes, but only for the selected row. Hovering answers
        // the question the table raises and the box cannot — "what does THAT one carry" — without
        // changing what is selected and losing your place.
        tree.setRowFactory(t -> {
            var row = new javafx.scene.control.TreeTableRow<Row>();
            row.itemProperty().addListener((o, was, is) -> row.setTooltip(tooltipFor(is)));
            return row;
        });

        // The tree takes whatever the window has spare; at its preferred height the rest of a resized
        // window was dead space below the buttons.
        Cols.fill(tree);

        return vbox().spacing(8).padding(12).nodes(
                label("Accounts and tokens").style("-fx-font-weight: bold;"),
                label("One token per scope group, so each expires on its own and an unused mail grant"
                        + " cannot take Sheets down with it. The narrowest token that covers a request is"
                        + " the one used.").wrapText(true).style("-fx-font-size: 11px; -fx-text-fill: #666;"),
                luvjfx.Fx.fx(tree),
                detail,
                hbox().spacing(6).nodes(label("email"), email, label("client"), client),
                hbox().spacing(6).nodes(grant, remove, up, down, forget),
                hbox().spacing(6).nodes(removeUnused, label("idle days (0 = never used)"), unusedDays,
                        googlePage, copy, reload),
                label("In the table:   Del removes a token   ·   U prefers it sooner   ·   D prefers it"
                        + " later   ·   G grants   ·   hover any row for its exact scopes")
                        .style("-fx-font-size: 11px; -fx-text-fill: #777;"),
                status.wrapText(true).style("-fx-text-fill: #1b5e20;")).node;
    }

    /** Everything the row knows, for a hover — the exact scopes above all, which is the real question. */
    private static javafx.scene.control.Tooltip tooltipFor(Row row) {
        if (row == null || row.token() == null) return null;
        var t = row.token();
        var tip = new javafx.scene.control.Tooltip(
                t.label() + "   (" + t.account() + ")\n"
                        + t.detail() + "\n"
                        + "Google classes this: " + t.tier().label + "\n"
                        + "Used " + TokenInfo.count(t.useCount()) + " time(s), last "
                        + AccountsTable.ago(t.lastUsed()) + "\n\n"
                        + String.join("\n", t.scopes()));
        tip.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 11px;");
        tip.setShowDelay(javafx.util.Duration.millis(400));
        // Long enough to actually read a dozen scope URLs; the default hides while you are still on the
        // second line, which makes the tooltip worse than useless for exactly the case it is here for.
        tip.setShowDuration(javafx.util.Duration.seconds(60));
        tip.setWrapText(false);
        return tip;
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

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

    /**
     * The live tab's own refresh, so the daily health check can put what it found on screen.
     *
     * <p>Held statically because the sweep runs on a background thread minutes or hours after this pane was
     * built, and it has no other way back to it. Null until the tab exists, which is the common case: the
     * check almost always runs with no window open at all.
     */
    private static Runnable liveRefresh;

    private AccountsPane() {
    }

    /** Called by {@link HealthDaily} after a sweep. Does nothing when nobody is looking. */
    static void refreshIfShowing() {
        var refresh = liveRefresh;
        if (refresh != null && MainWindow.isShowing()) refresh.run();
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

        // Across the top rather than in the table, because a red cell in the third column of a table
        // somebody has not looked at today is not a warning, it is a fact waiting to be discovered by a
        // failing batch. Empty and invisible when everything is well.
        var banner = label("").wrapText(true);
        banner.attr(l -> l.setVisible(false));

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
            showHealth(core, banner);
        };
        liveRefresh = refresh;
        refresh.run();

        tree.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> {
            if (is == null) return;
            if (is.getValue().account() != null) ((TextField) email.node).setText(is.getValue().account());
            var t = is.getValue().token();
            ((TextArea) detail.node).setText(t == null ? "" :
                    t.label() + "\n" + t.detail()
                            + "\n\nGoogle classes this: " + t.tier().label
                            + "\nUsed " + TokenInfo.count(t.useCount()) + " time(s)"
                            + "\n\nHealth: " + t.state().label
                            + "   ·   checked " + AccountsTable.ago(t.lastCheckedAt())
                            + "   ·   last healthy " + AccountsTable.ago(t.lastHealthyAt())
                            + "   ·   granted " + t.lifeDays() + " day(s) "
                            + (t.staleSince() > 0 ? "before it died" : "ago")
                            + (t.healthNote() == null || t.healthNote().isBlank() ? ""
                               : "\n" + t.healthNote())
                            + "\n\nScopes:\n  " + String.join("\n  ", t.scopes()));
        });

        var grant = button("Grant...  (G)");
        var reauth = button("Re-authenticate  (R)");
        var checkHealth = button("Check health now  (H)");
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

        /*
         * Consent again for what has expired, keeping the scopes exactly as they were.
         *
         * Not a variant of Grant, because Grant asks which powers to give and that is a decision — the one
         * decision that must not be re-taken here. What has happened is that a permission already agreed
         * has expired, so the scopes come from the stored token: a catalogue group re-consents with the
         * catalogue's scopes, a legacy or hand-written group with the precise set it was carrying.
         *
         * A token row re-consents that group. An account row takes every credential on the account the
         * daily check has found broken, which is the usual shape of it — a client's whole set of tokens
         * dies on the same day, not one of them.
         */
        Runnable doReauth = () -> {
            var sel = tree.getSelectionModel().getSelectedItem();
            var who = sel != null && sel.getValue().account() != null ? sel.getValue().account()
                    : ((TextField) email.node).getText().trim();
            if (who.isEmpty()) {
                status.text("Select an account or a token first.");
                return;
            }
            var one = sel == null ? null : sel.getValue().token();
            var what = one == null ? "every failing credential on " + who : one.label() + " for " + who;
            if (!Confirm.ask("Re-authenticate", "Consent again for " + what + "?\n\n"
                    + "A browser window opens, one per credential, and the scopes are exactly the ones\n"
                    + "already granted — nothing is widened. Standing permissions and the audit are\n"
                    + "untouched.", "Re-authenticate", "Cancel")) return;
            status.text("Consent windows will open. Sign in as " + who + ".");
            new Thread(() -> {
                try {
                    accounts.reauth(new Asks.Reauth(who, one == null ? null : one.group(), 8888));
                    AuthUrlWindow.dismiss();
                    javafx.application.Platform.runLater(() -> {
                        status.text("Re-authenticated. The health column is checked again immediately,"
                                + " so it should read healthy now.");
                        refresh.run();
                    });
                } catch (Exception ex) {
                    AuthUrlWindow.dismiss();
                    javafx.application.Platform.runLater(() -> status.text(String.valueOf(ex.getMessage())));
                }
            }, "wallet-reauth").start();
        };
        reauth.attr(b -> b.setOnAction(e -> doReauth.run()));

        // The same check the daily sweep runs, on demand — because the moment anyone wants to know is the
        // moment something has just failed, not tomorrow at breakfast.
        Runnable doCheck = () -> {
            var sel = tree.getSelectionModel().getSelectedItem();
            var one = sel == null ? null : sel.getValue().token();
            status.text("Checking with Google, read-only...");
            new Thread(() -> {
                try {
                    var report = core.health.run(one == null ? null : one.account(),
                            one == null ? null : one.group());
                    javafx.application.Platform.runLater(() -> {
                        status.text(report.headline());
                        refresh.run();
                    });
                } catch (Exception ex) {
                    javafx.application.Platform.runLater(() -> status.text(String.valueOf(ex.getMessage())));
                }
            }, "wallet-health-now").start();
        };
        checkHealth.attr(b -> b.setOnAction(e -> doCheck.run()));

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
                case R -> doReauth.run();
                case H -> doCheck.run();
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
                banner,
                luvjfx.Fx.fx(tree),
                detail,
                hbox().spacing(6).nodes(label("email"), email, label("client"), client),
                hbox().spacing(6).nodes(grant, reauth, checkHealth),
                hbox().spacing(6).nodes(remove, up, down, forget),
                hbox().spacing(6).nodes(removeUnused, label("idle days (0 = never used)"), unusedDays,
                        googlePage, copy, reload),
                label("In the table:   R re-authenticates   ·   H checks health now   ·   G grants   ·"
                        + "   Del removes a token   ·   U prefers it sooner   ·   D prefers it later   ·"
                        + "   hover any row for its exact scopes")
                        .style("-fx-font-size: 11px; -fx-text-fill: #777;"),
                status.wrapText(true).style("-fx-text-fill: #1b5e20;")).node;
    }

    /**
     * The banner: what the daily check found, and the one sentence that explains a whole client's worth of
     * deaths when there is one.
     *
     * <p>The client-level hint is folded in here rather than left on the Clients tab because this is where
     * the question gets asked. Three tokens all stale on the same morning is not three problems, and being
     * told "these keep dying at seven days, which is what a Cloud project still in Testing does" is the
     * difference between re-authenticating weekly forever and publishing the app once.
     */
    private static void showHealth(WalletCore core, luvjfx.FxLabel banner) {
        if (!core.keyring.unlocked()) {
            banner.attr(l -> l.setVisible(false));
            return;
        }
        var broken = core.accounts().stream().flatMap(a -> a.tokens().stream())
                .filter(t -> t.state().bad()).toList();
        if (broken.isEmpty()) {
            var everChecked = core.accounts().stream().flatMap(a -> a.tokens().stream())
                    .anyMatch(t -> t.lastCheckedAt() > 0);
            banner.text(everChecked ? "" : "Credential health has not been checked yet. Press H to check"
                    + " now, or leave it to the daily check.");
            banner.style("-fx-font-size: 11px; -fx-text-fill: #777;");
            banner.attr(l -> l.setVisible(!everChecked));
            return;
        }
        var hint = core.orgs().stream().map(uskoag.wallet.wire.OrgInfo::expiryHint)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        banner.text(broken.size() + " credential(s) are not working: "
                + broken.stream().map(t -> t.account() + " / " + t.label()).distinct()
                .reduce((a, b) -> a + "   ·   " + b).orElse("")
                + ".   Select one and press R to re-authenticate."
                + (hint == null ? "" : "\n" + hint));
        banner.style("-fx-text-fill: #b71c1c; -fx-font-weight: bold;"
                + " -fx-background-color: #fdecea; -fx-padding: 6;");
        banner.attr(l -> l.setVisible(true));
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
                        + AccountsTable.ago(t.lastUsed()) + "\n"
                        + "Health: " + t.state().label + ", checked " + AccountsTable.ago(t.lastCheckedAt())
                        + ", last healthy " + AccountsTable.ago(t.lastHealthyAt()) + "\n"
                        + (t.healthNote() == null || t.healthNote().isBlank() ? "" : t.healthNote() + "\n")
                        + "\n" + String.join("\n", t.scopes()));
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

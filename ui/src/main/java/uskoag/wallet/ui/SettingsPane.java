package uskoag.wallet.ui;

import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import uskoag.wallet.daemon.WalletCore;
import uskoag.wallet.wire.Span;
import uskoag.wallet.wire.Tier;

import static luvjfx.Fx.button;
import static luvjfx.Fx.checkBox;
import static luvjfx.Fx.hbox;
import static luvjfx.Fx.label;
import static luvjfx.Fx.scrollPane;
import static luvjfx.Fx.textField;
import static luvjfx.Fx.vbox;

/**
 * The settings, which live inside the keyring and are reachable only from here.
 *
 * <p>They used to sit in {@code wallet.toml} beside it, which meant anything running as this user could
 * write {@code readRequiresRule = false} and switch the whole policy layer off without ever touching the
 * wallet. A setting able to disable a control has to be protected the way the control is — so it is
 * encrypted with everything else, it cannot be read or written while locked, and there is deliberately no
 * verb for it over the socket.
 *
 * <p>Each one says what it costs, not just what it does. A settings panel that only names its switches
 * leaves you to guess which way is safer, and the guess is usually "whichever stops the prompting".
 */
public final class SettingsPane {

    private SettingsPane() {
    }

    public static Node build(WalletCore core) {
        var s = core.settings;

        var readRule = checkBox("Reading a document needs a standing permission");
        var mutateRule = checkBox("Changing a document needs a standing permission");
        var destructivePhrase = checkBox("Irreversible operations re-ask for the passphrase");
        var openBrowser = checkBox("Open the default browser automatically during consent");
        var dailyHealth = checkBox("Check every credential once a day, read-only");

        var ops = textField();
        var autoLock = textField();
        var healthPrompt = textField();
        var status = label("");

        Runnable load = () -> {
            ((CheckBox) readRule.node).setSelected(s.readRequiresRule);
            ((CheckBox) mutateRule.node).setSelected(s.mutateRequiresRule);
            ((CheckBox) destructivePhrase.node).setSelected(s.destructiveNeedsPassphrase);
            ((CheckBox) openBrowser.node).setSelected(s.openBrowserAutomatically);
            ((CheckBox) dailyHealth.node).setSelected(s.dailyHealthCheck);
            ((TextField) ops.node).setText(String.valueOf(s.destructiveOps));
            ((TextField) autoLock.node).setText(String.valueOf(s.autoLockMinutes));
            ((TextField) healthPrompt.node).setText(String.valueOf(s.healthPromptMinutes));
            status.text("Loaded from the keyring. Nothing is written until you press Save.");
        };
        load.run();

        var save = button("Save").defaultButton(true);
        var reload = button("Reload").cancelButton(true);
        var changePass = button("Change passphrase...");
        changePass.attr(b -> b.setOnAction(e -> PassphraseWindow.show(core)));

        save.attr(b -> b.setOnAction(e -> {
            try {
                var wantedOps = positive(ops, "the operation budget");
                var wantedLock = nonNegative(autoLock, "auto-lock");
                var wantedPrompt = positive(healthPrompt, "the health prompt timeout");

                s.readRequiresRule = ((CheckBox) readRule.node).isSelected();
                s.mutateRequiresRule = ((CheckBox) mutateRule.node).isSelected();
                s.destructiveNeedsPassphrase = ((CheckBox) destructivePhrase.node).isSelected();
                s.openBrowserAutomatically = ((CheckBox) openBrowser.node).isSelected();
                s.destructiveOps = wantedOps;
                s.autoLockMinutes = wantedLock;
                s.dailyHealthCheck = ((CheckBox) dailyHealth.node).isSelected();
                s.healthPromptMinutes = wantedPrompt;
                core.saveSettings();
                status.text("Saved into the keyring. Irreversible grants stop at "
                        + s.destructiveOps + " operations or "
                        + Span.describe(Tier.DESTRUCTIVE.maxMinutes) + ", whichever comes first.");
            } catch (Exception ex) {
                status.text(String.valueOf(ex.getMessage()));
            }
        }));
        reload.attr(b -> b.setOnAction(e -> load.run()));

        var body = vbox().spacing(10).padding(4).nodes(
                label("What gets asked").style("-fx-font-weight: bold;"),
                readRule,
                note("Off means any document this account can reach is readable by any tool here without a"
                        + " word. Unlocking the wallet is not the same as approving a document — the whole"
                        + " point of the approved list is that it is narrower than the account."),
                mutateRule,
                note("Off means edits land silently. They are recoverable from Google's version history,"
                        + " which is why this is a separate switch from reading."),
                destructivePhrase,
                note("On, a deletion or a share asks for the passphrase again. Off, unlocking once in the"
                        + " morning leaves every irreversible operation for the rest of the day one"
                        + " keypress away — and a keypress is something any process running as you can"
                        + " synthesise. The passphrase is the only thing here that proves a person."),

                label("Limits").style("-fx-font-weight: bold;"),
                row("Operation budget per irreversible approval", ops,
                        "Counts before clocks. A span bounds your sitting; it does nothing to bound a"
                                + " runaway loop, which can issue ten thousand deletions inside an hour"
                                + " and have every one of them be inside what you approved."),
                note("How long a permission may stand is fixed and not settable here: read "
                        + Span.describe(Tier.READ.maxMinutes) + ", change "
                        + Span.describe(Tier.MUTATE.maxMinutes) + ", irreversible "
                        + Span.describe(Tier.DESTRUCTIVE.maxMinutes)
                        + ". Nothing is ever granted without an expiry. A ceiling anyone can raise is not a"
                        + " ceiling, and these can afford to be short because extending costs one click —"
                        + " or one passphrase on the irreversible tier."),

                label("Housekeeping").style("-fx-font-weight: bold;"),
                openBrowser,
                note("With several org accounts on one machine the default browser is often signed in as"
                        + " the wrong one. The copyable-link window appears either way."),
                row("Auto-lock after idle minutes", autoLock,
                        "0 disables it. Idle means idle for the wallet, not for you — every approval and"
                                + " every proxied call defers it, so a batch running unattended for two"
                                + " hours does not trip it. This is the second lock and it answers a"
                                + " different question from an approval: the approval says what may be"
                                + " touched, this says for how long anything at all may be."),

                label("Credential health").style("-fx-font-weight: bold;"),
                dailyHealth,
                note("Once a day, every stored credential is put in front of Google with read-only calls"
                        + " only — the refresh token is exchanged, and one cheap read is made per API it"
                        + " covers. Off means a credential Google has quietly stopped honouring is"
                        + " discovered halfway through real work instead of at breakfast. It is also the"
                        + " only thing keeping two six-month clocks moving: the one that revokes an unused"
                        + " refresh token, and the one that deletes an unused OAuth client — which takes"
                        + " credentials.json with it, and that cannot be rebuilt by consenting again."),
                note("What it cannot do is stop the seven-day expiry a Cloud project still in Testing"
                        + " applies to every token it issues. Nothing can, except publishing the app. The"
                        + " Clients tab says which of your clients looks like it is in that state, and how"
                        + " it worked that out."),
                row("Hide the health passphrase box after minutes", healthPrompt,
                        "This is the one prompt in the wallet with nothing waiting behind it, so it is also"
                                + " the one that neither sits on top nor takes the caret off whatever you"
                                + " are typing. Ignoring it costs nothing: the check runs the next time the"
                                + " wallet is unlocked for any reason at all."),

                label("Passphrase").style("-fx-font-weight: bold;"),
                hbox().spacing(8).nodes(changePass),
                note("Only the keyring file is rewritten. Every stored token survives, the audit stays"
                        + " readable across the change because its column key lives inside the keyring"
                        + " rather than being derived from the passphrase, and nothing at Google is"
                        + " touched."),

                hbox().spacing(8).nodes(save, reload),
                status.wrapText(true).style("-fx-font-size: 11px; -fx-text-fill: #1b5e20;"));

        var scroll = scrollPane();
        ((javafx.scene.control.ScrollPane) scroll.node).setContent(body.node);
        ((javafx.scene.control.ScrollPane) scroll.node).setFitToWidth(true);
        Cols.fill((javafx.scene.layout.Region) scroll.node);

        return vbox().spacing(8).padding(12).nodes(
                        label("Settings").style("-fx-font-weight: bold;"),
                        label("Stored inside the keyring, not in a file beside it, because a setting that"
                                + " can switch off a control has to be as protected as the control.")
                                .wrapText(true).style("-fx-font-size: 11px; -fx-text-fill: #666;"))
                .add(scroll.node).node;
    }

    private static luvjfx.FxVBox row(String title, luvjfx.FxTextField field, String why) {
        ((TextField) field.node).setPrefColumnCount(8);
        return vbox().spacing(3).nodes(
                hbox().spacing(8).nodes(field, label(title)),
                note(why));
    }

    private static luvjfx.FxLabel note(String text) {
        return label(text).wrapText(true).style("-fx-font-size: 11px; -fx-text-fill: #666;");
    }

    private static int positive(luvjfx.FxTextField f, String what) {
        var n = nonNegative(f, what);
        if (n <= 0) throw new IllegalArgumentException(what + " must be greater than zero. Nothing saved.");
        return n;
    }

    private static int nonNegative(luvjfx.FxTextField f, String what) {
        try {
            var n = Integer.parseInt(((TextField) f.node).getText().trim());
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be a whole number. Nothing saved.");
        }
    }
}

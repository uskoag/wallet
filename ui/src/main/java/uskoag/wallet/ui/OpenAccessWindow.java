package uskoag.wallet.ui;

import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.stage.Stage;
import uskoag.wallet.daemon.WalletCore;
import uskoag.wallet.wire.ApprovalAnswer;
import uskoag.wallet.wire.Match;
import uskoag.wallet.wire.ResourceRef;
import uskoag.wallet.wire.Span;
import uskoag.wallet.wire.Tier;

import java.util.Arrays;
import java.util.List;

import static luvjfx.Fx.button;
import static luvjfx.Fx.choiceBox;
import static luvjfx.Fx.hbox;
import static luvjfx.Fx.label;
import static luvjfx.Fx.passwordField;
import static luvjfx.Fx.radioButton;
import static luvjfx.Fx.scene;
import static luvjfx.Fx.separator;
import static luvjfx.Fx.vbox;

/**
 * Open the door for a while, on purpose, from the tray.
 *
 * <p><b>Why it exists.</b> His words: <i>"the overall nagging is just too much, I am having to approve a
 * lot"</i>. Everything built before this answered that with advice — tick a wider box, run a CLI verb — and
 * advice is not an answer to being interrupted forty times. This is the answer: one tray click, the
 * passphrase twice, and the wallet stops asking for a bounded stretch.
 *
 * <p><b>What it writes</b> is exactly what {@code policy quiet} writes, through the same engine: one rule
 * with no profile, no account, no api and no resource, at one tier, with an expiry. Two doors onto one
 * mechanism, so it shows in {@code policy list}, it is revocable by id, it is audited, and it dies on its
 * own. Deliberately not {@code readRequiresRule = false} in Settings, which silences a tier with no expiry
 * at all, appears in no listing, and leaves nothing to revoke.
 *
 * <p><b>Three bounds, and the third is not negotiable.</b> The clock, which is shorter here than for a rule
 * about one named document — see {@link Tier#blanketMaxMinutes}, a day to read anything against a week to
 * read one thing. The passphrase, typed twice, because a click can be synthesised by anything running as
 * this user and because this is the widest grant the wallet can issue. And <b>no irreversible tier, from
 * this window or any other</b>: delete, move and share keep asking, one named document at a time, which is
 * the whole point of that tier.
 *
 * <p><b>What is lost, plainly.</b> While one of these stands, a poisoned document that reaches any tool can
 * read — or, at the higher setting, write — anything these accounts can, and the first anyone knows of it is
 * the audit. That is a real reduction in protection. It is the one being asked for; the expiry is what keeps
 * it from being permanent and the Permissions tab is what keeps it from being invisible.
 */
public final class OpenAccessWindow {

    /**
     * The spans offered per tier, longest last, and none of them the ceiling by default.
     *
     * <p>Short defaults are nearly free here for the reason already recorded on {@link Tier#maxMinutes}:
     * re-opening costs one passphrase, while a window that turned out to be too long is only ever
     * discovered afterwards. Offering the maximum first would make the maximum the habit.
     */
    private static final List<Integer> READ_SPANS = List.of(60, 240, 720, 1440);
    private static final List<Integer> WRITE_SPANS = List.of(30, 60, 120, 240);

    private static final int READ_DEFAULT = 240, WRITE_DEFAULT = 60;

    /** One at a time, like the unlock prompt, so a double tray click does not stack two of these. */
    private static Stage open;

    private OpenAccessWindow() {
    }

    public static void show(WalletCore core, Runnable onGranted) {
        if (open != null && open.isShowing()) {
            Ui.toFront(open);
            return;
        }
        // Nothing here can work over a locked keyring: there is no rule store to write to and no passphrase
        // in memory to check the typed one against. Ask for the unlock first and come back, rather than
        // showing a window whose buttons would all fail.
        if (!core.keyring.unlocked()) {
            UnlockWindow.show(core, () -> show(core, onGranted),
                    "Locked — unlock first, then open access.");
            return;
        }

        var stage = new Stage();
        open = stage;
        stage.setAlwaysOnTop(true);
        stage.setTitle(uskoag.wallet.wire.Brand.titled("open access"));
        AppIcon.applyTo(stage);

        var group = new ToggleGroup();
        var readOnly = radioButton("Read anything  —  list, open, download, export");
        var readWrite = radioButton("Read and change anything  —  also create, update, append, draft");
        ((RadioButton) readOnly.node).setToggleGroup(group);
        ((RadioButton) readWrite.node).setToggleGroup(group);
        ((RadioButton) readOnly.node).setSelected(true);

        var span = choiceBox(Integer.class);
        @SuppressWarnings("unchecked")
        var spanBox = (javafx.scene.control.ChoiceBox<Integer>) span.node;
        spanBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Integer m) {
                return m == null ? "" : Span.describe(m);
            }

            @Override
            public Integer fromString(String s) {
                return Span.parse(s);
            }
        });

        var ceiling = label("");
        Runnable retier = () -> {
            var write = ((RadioButton) readWrite.node).isSelected();
            spanBox.getItems().setAll(write ? WRITE_SPANS : READ_SPANS);
            spanBox.setValue(write ? WRITE_DEFAULT : READ_DEFAULT);
            var tier = write ? Tier.MUTATE : Tier.READ;
            ceiling.text("Whatever is chosen, a rule this wide stops at "
                    + Span.describe(tier.blanketMaxMinutes) + ". Delete, move and sharing are never covered"
                    + " and keep asking one document at a time.");
        };
        readOnly.attr(b -> b.setOnAction(e -> retier.run()));
        readWrite.attr(b -> b.setOnAction(e -> retier.run()));
        retier.run();

        var first = passwordField();
        var again = passwordField();
        var status = label("");
        var go = button("Open access").defaultButton(true);
        var cancel = button("Cancel").cancelButton(true);

        Runnable grant = () -> {
            var typed = ((PasswordField) first.node).getText().toCharArray();
            var repeat = ((PasswordField) again.node).getText().toCharArray();
            try {
                if (!Arrays.equals(typed, repeat)) {
                    fail(status, first, again, "The two entries did not match. Both boxes cleared —"
                            + " type it again.");
                    return;
                }
                // Verified against the passphrase this wallet was unlocked with, exactly as the
                // irreversible tier of the approval dialog does it. Same secret, same check.
                if (!core.keyring.verify(typed)) {
                    fail(status, first, again, "That is not this wallet's passphrase. Both boxes cleared."
                            + " Nothing was sent anywhere; the check is local.");
                    return;
                }
                var write = ((RadioButton) readWrite.node).isSelected();
                var tier = write ? Tier.MUTATE : Tier.READ;
                var minutes = spanBox.getValue() == null
                        ? (write ? WRITE_DEFAULT : READ_DEFAULT) : spanBox.getValue();

                // Every field null: every profile, every account, every api, every document. The clamp to
                // the blanket ceiling happens in PolicyEngine, keyed on there being no resource — not here,
                // because a ceiling this window enforced for itself would be a ceiling `policy quiet` did
                // not.
                // ops = -1, meaning no operation budget, and NOT 0.
                //
                // PolicyRule.exhausted() is `opsBudget >= 0 && opsUsed >= opsBudget`, so a budget of zero
                // is a rule that is exhausted the instant it is written and matches nothing, ever. This
                // window would have taken the passphrase twice, reported success, shown the rule in the
                // Permissions tab — and changed nothing at all, with the approval dialog still appearing
                // for every document. Found by the preview harness driving the window, not by reading it.
                //
                // An operation budget belongs to the irreversible tier, which this can never be: counts
                // bound a loop where a clock does not, and there is nothing irreversible here to count.
                var rule = core.policy.remember(null, null, null,
                        new ResourceRef(null, null, null), tier,
                        new ApprovalAnswer(true, true, -1, minutes, Match.EXACT, "open access"),
                        "open access from the tray");

                stage.close();
                open = null;
                Tray.note(uskoag.wallet.wire.Brand.NAME, "Open for " + tier + " until "
                        + rule.until().replace("until ", "") + ".  Revoke: Permissions tab, or"
                        + " uskoag-walletcli policy revoke " + rule.id);
                if (onGranted != null) onGranted.run();
            } catch (Exception e) {
                fail(status, first, again, "Could not write the rule: " + e.getMessage());
            } finally {
                Arrays.fill(typed, '\0');
                Arrays.fill(repeat, '\0');
            }
        };

        go.attr(b -> b.setOnAction(e -> grant.run()));
        cancel.attr(b -> b.setOnAction(e -> {
            stage.close();
            open = null;
        }));
        first.attr(f -> f.setOnAction(e -> again.node.requestFocus()));
        again.attr(f -> f.setOnAction(e -> grant.run()));

        var body = vbox().spacing(9).padding(18).nodes(
                label("Open access for a while").style("-fx-font-size: 18px; -fx-font-weight: bold;"),
                label("Stops the approval dialog appearing at all, for every account and every document,"
                        + " until the time runs out. This is the widest thing the wallet can be asked for,"
                        + " which is why it costs the passphrase twice.").wrapText(true),
                separator(),
                readOnly, readWrite,
                hbox().spacing(8).nodes(label("For:"), span),
                ceiling.wrapText(true).style("-fx-font-size: 11px; -fx-text-fill: #7d6100;"),
                separator(),
                label("Wallet passphrase").style("-fx-font-weight: bold;"),
                first,
                label("Wallet passphrase again"),
                again,
                status.wrapText(true).style("-fx-text-fill: #b71c1c;"),
                hbox().spacing(8).nodes(go, cancel),
                label("Enter moves to the second box, then opens   |   Esc closes this   |   it shows in"
                        + " the Permissions tab and can be revoked there at any moment")
                        .wrapText(true).style("-fx-font-size: 11px; -fx-text-fill: #5b564f;"));

        var sc = scene(body.style(Ui.INK), 560, 480);
        Ui.escCloses(sc, stage, () -> open = null);
        stage.setScene(sc);
        // Content-sized like every other window here, because the ceiling note and the error line both wrap
        // to a second line and a fixed height is wrong in one direction or the other for each of them.
        Ui.fitToContent(stage, body.node, 560, "#ffffff");
        Ui.grabFocus(stage, first.node);
    }

    private static void fail(luvjfx.FxLabel status, luvjfx.FxPasswordField first,
                             luvjfx.FxPasswordField again, String message) {
        status.text(message);
        ((PasswordField) first.node).clear();
        ((PasswordField) again.node).clear();
        first.node.requestFocus();
    }
}

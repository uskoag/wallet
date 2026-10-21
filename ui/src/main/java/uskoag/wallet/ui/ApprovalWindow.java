package uskoag.wallet.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import uskoag.wallet.daemon.WalletSettings;
import uskoag.wallet.wire.ApprovalAnswer;
import uskoag.wallet.wire.ApprovalAsk;
import uskoag.wallet.wire.Match;
import uskoag.wallet.wire.Span;
import uskoag.wallet.wire.Tier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static luvjfx.Fx.button;
import static luvjfx.Fx.checkBox;
import static luvjfx.Fx.flowPane;
import static luvjfx.Fx.hbox;
import static luvjfx.Fx.label;
import static luvjfx.Fx.passwordField;
import static luvjfx.Fx.scene;
import static luvjfx.Fx.separator;
import static luvjfx.Fx.textField;
import static luvjfx.Fx.vbox;

/**
 * The dialog that has to be readable in two seconds.
 *
 * <p>It names the actual operation rather than asking for "write access", and it names the document
 * rather than its id, because a prompt you cannot evaluate is a prompt you will click through — and a
 * habit of clicking through is worse than no dialog, since it launders consent it never obtained. The
 * correlation code is in large type for the same reason: with several agent runs in flight, "which one is
 * asking" is the only question that matters, and the client printed the same characters to its stderr.
 *
 * <p>Colour tracks elevation, since that is judged before any word is read: green to read, amber to
 * change, red for anything that cannot be undone.
 *
 * <p><b>Every answer is one function key</b>, pressed once after the window has settled for two seconds:
 * F2 or Esc refuses, F3 ticks breadth, F4 grants once, F6 and up grant a span. Function keys are keys
 * that composing text never emits, so a window that raised itself over someone mid-sentence cannot be
 * answered by the sentence — which is what happened when the widest span was the default button and the
 * next Enter of a paragraph granted a seven-day permission. Nothing here is focusable and nothing is a
 * default button, so Enter, Space and Tab do nothing; the mouse still works.
 *
 * <p>The irreversible tier, and any grant that covers documents nobody named, asks for the passphrase
 * again before anything can be granted, and its spans stop at one day.
 */
public final class ApprovalWindow {

    /*
     * Every colour here is checked against the surface it is actually drawn on, which is not white.
     *
     * The tier hues stay what they were — colour is judged before any word is read and green/amber/red is
     * the right three. What was wrong was the LIGHTNESS, in both places it mattered. White on the old
     * #e65100 band is 3.8:1, under the 4.5:1 that ordinary text needs, so the one line whose whole job is
     * to say IRREVERSIBLE or CHANGE at a glance was the least readable thing on the window. And the greys
     * carried over from a white-background dialog — #999, #888, #777 — sit at 2.7:1 to 4.2:1 on a tinted
     * one, which is why explanatory text read as white-on-orange rather than as text.
     *
     * So: AMBER is deepened until white clears 5:1 on it, and the grey ladder is replaced by two warm
     * inks that clear 4.5:1 on ALL THREE tints. Measured against the worst of the three in each case,
     * never against white.
     */
    private static final String
            RED = "#b71c1c",        // white on it 6.6:1 · as text on its tint 5.7:1
            AMBER = "#bf4b00",      // white on it 5.0:1 · as text on its tint 4.6:1
            GREEN = "#1b5e20",      // white on it 8.0:1 · as text on its tint 9.6:1
            WARN = "#7d6100",       // 5.4:1 — the colour of "this is not what you think it is"
            MUTED = "#5b564f",      // 6.3:1 — the 11px explanatory text, which is most of this window
            FAINT = "#6e6960";      // 4.7:1 — the 10px command line and the countdown, and no fainter

    /** The two secondary text styles, pre-mixed, because between them they are most of this window. */
    private static final String
            NOTE = "-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";",
            ASIDE = "-fx-font-size: 10px; -fx-text-fill: " + FAINT + ";";

    /**
     * How long the window ignores the keyboard after appearing.
     *
     * <p>Long enough to swallow the rest of a word somebody was typing when it took their focus, short
     * enough that a person who is waiting for it does not notice. Nobody reads a permission question
     * and decides inside a second, so nothing legitimate is lost.
     */
    private static final long SETTLE_MS = 2000;

    /**
     * Deliberately under the client's 300s read timeout in {@code GrantInitializer}. The wallet has to
     * give up first, or the call dies while this window is still open and a later click grants a rule for
     * a request that no longer exists.
     */
    private static final int WAIT_SECONDS = 240;

    /** Fixed, because the wrapped-text measurement needs the width it will actually be laid out at. */
    private static final int WIDTH = 660;

    /**
     * The tallest the command line is allowed to be: about three lines, then it scrolls.
     *
     * <p>A {@code maxHeight} and deliberately not a {@code prefViewportHeight}. {@code VBox} clamps each
     * child's preferred height between its min and its max, so a max lets a one-line command occupy one
     * line while a page-long one stops here — which is what keeps {@link Ui#fitToContent} exact. A
     * preferred viewport height would reserve three lines for every command, which is the dead space this
     * window was rebuilt to get rid of.
     */
    private static final int COMMAND_MAX_H = 74;

    private ApprovalWindow() {
    }

    public static ApprovalAnswer ask(ApprovalAsk ask, WalletSettings settings, Predicate<char[]> verify) {
        var out = new AtomicReference<>(ApprovalAnswer.deny());
        var done = new CountDownLatch(1);
        var stage = new AtomicReference<Stage>();

        javafx.application.Platform.runLater(() -> stage.set(build(ask, settings, verify, answer -> {
            out.set(answer);
            done.countDown();
        })));
        try {
            if (!done.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                out.set(ApprovalAnswer.timedOut(WAIT_SECONDS));
                javafx.application.Platform.runLater(() -> {
                    var s = stage.get();
                    if (s != null) s.close();
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out.get();
    }

    private static Stage build(ApprovalAsk ask, WalletSettings settings, Predicate<char[]> verify,
                               Consumer<ApprovalAnswer> answer) {
        var frame = Look.frame("approval", false);
        var stage = frame.stage;
        stage.initModality(Modality.NONE);

        var danger = ask.tier() == Tier.DESTRUCTIVE;
        var accent = accent(ask.tier());

        // A blanket grant names no document, because covering every document is the point of it. That has
        // to read as deliberate breadth and not as a failed name lookup: "unidentified resource" in amber
        // is what this window says when it asked Google for a title and did not get one, which is a
        // completely different situation and one the person is meant to be suspicious of.
        var blanket = ask.resource() == null || ask.resource().id() == null;
        var unresolved = !blanket
                && (ask.resource().label() == null || ask.resource().label().isBlank());
        var cap = ask.tier().maxMinutes;
        // Two independent reasons to demand the passphrase, and they are different reasons: DESTRUCTIVE is
        // gated because it cannot be undone, a wide grant because it covers documents nobody has named.
        var locked = (danger && settings.destructiveNeedsPassphrase) || ask.requiresPassphrase();

        var deny = button("F2 · Deny");
        var custom = textField();
        var phrase = passwordField();
        var closing = label("");
        var gateNote = label("");
        var choices = new ArrayList<luvjfx.FxButton>();

        /*
         * How wide the rule is, which until now could not be chosen here at all.
         *
         * The dialog only ever wrote an EXACT rule, so one logical action over thirty files asked thirty
         * times. Choosing a longer span did not help and could not: the span sets how long the rule
         * stands, never what it covers, and the reasonable reading of "1 hour" is "stop asking me for an
         * hour". Only the CLI could write a wider match, which is the wrong way round - the person being
         * interrupted is the one who should be able to stop the interruption.
         *
         * Off by default, and it stays off by default. Cheap is how a control becomes a reflex, and the
         * everyday case really is one document.
         *
         * The safety comes from binding, not from refusing. Anything ticked here is pinned to the session
         * that asked, so it dies when that run ends and cannot be inherited by the next command - see
         * PolicyEngine.remember. Together with the tier ceiling and, on the irreversible tier, the
         * operation budget, the widest thing this can produce is "this batch, this run, up to N
         * operations, up to the ceiling".
         */
        // "this run" was the old wording and it was read, reasonably, as "until I say otherwise". It does
        // not mean that: a session is derived from process ancestry when nothing exported one, and that
        // walk stops at the first link the JDK cannot see, so in practice it is usually per-invocation.
        // Six identical READ dialogs for one spreadsheet arrived inside two and a half minutes because
        // each grant died with the command that asked for it, and ticking this box is exactly what a
        // person does when they are being asked repeatedly. Say "command", which is what it is worth.
        var wide = checkBox("Apply to every item THIS ONE COMMAND touches");
        ((javafx.scene.control.CheckBox) wide.node).setSelected(false);

        // Breadth is a plain checkbox (F3). It used to cost typing the request code, and that
        // extra step was answered by giving long open-access grants instead, which is wider than anything
        // the checkbox can do. The passphrase still stands in front of the tiers that need it.

        Consumer<ApprovalAnswer> finish = a -> {
            answer.accept(a);
            stage.close();
        };

        // Re-armable, because there are two moments when keys arrive that were not aimed at a choice:
        // when the window first appears over whatever someone was doing, and immediately after the
        // passphrase is submitted, when focus moves onto the span buttons and a repeated or held Enter
        // would land on one.
        var settleUntil = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() + SETTLE_MS);

        // Every answer is one function key, pressed once after the window has settled: an F-key is a key
        // that composing text never emits, so nothing typed at a window that stole focus can reach a
        // choice, and the settle time swallows what was already in flight when it appeared.
        //
        // Deliberately no chord anywhere here — see the hand-pain note in the global instructions.
        var actions = new java.util.LinkedHashMap<KeyCode, Runnable>();

        // Which button each key aims at, so the first press can be shown ON that button rather than only
        // described in a status line somewhere else in the window.
        var keyNodes = new java.util.LinkedHashMap<KeyCode, javafx.scene.Node>();

        var once = button("F4 · Once");
        once.attr(b -> b.setOnAction(e -> finish.accept(ApprovalAnswer.once())));
        choices.add(once);
        actions.put(KeyCode.F4, () -> finish.accept(ApprovalAnswer.once()));
        keyNodes.put(KeyCode.F4, once.node);

        var spanKeys = new KeyCode[]{KeyCode.F6, KeyCode.F7, KeyCode.F8, KeyCode.F9, KeyCode.F10};
        int k = 0;
        for (var span : Span.values()) {
            // Spans past this tier's ceiling are not offered at all, rather than offered and then silently
            // clamped: a button that does not do what it says is its own defect. "Forever" is therefore
            // never on screen, for any tier.
            if (!ask.tier().allows(span)) continue;
            var key = k < spanKeys.length ? spanKeys[k++] : null;
            var b = button((key == null ? "" : key.getName() + " · ") + span.label);
            Runnable act = () -> finish.accept(
                    grant(span, danger, settings.destructiveOps, breadth(wide)));
            b.attr(x -> x.setOnAction(e -> act.run()));
            choices.add(b);
            if (key != null) {
                actions.put(key, act);
                keyNodes.put(key, b.node);
            }
        }

        // No button here is focusable, and none is a default button.
        //
        // This used to set the *widest* span the tier allowed as the scene's default button. A JavaFX
        // default button fires on ENTER from anywhere in the scene whatever holds focus, and a focused
        // Button fires on SPACE. The window also raises itself always-on-top and pulls focus. So a
        // dialog appearing while someone was typing prose took the next space or Enter of that sentence
        // and turned it into the largest standing permission on offer — a week for READ, a day for
        // MUTATE. It happened, and it granted a seven-day read on a document nobody had been asked
        // about in any way the person could perceive: "the popup came and it just selected something
        // ... i have no clue".
        //
        // Managing focus was not enough, because it only moves which key is dangerous. Taking the
        // buttons out of the focus chain altogether removes the whole class: with nothing focusable
        // there is no key ENTER or SPACE can reach, whatever a person is in the middle of typing. The
        // buttons stay clickable, and the keyboard route is the F-keys above.
        for (var c : choices) ((Button) c.node).setFocusTraversable(false);
        ((Button) deny.node).setFocusTraversable(false);
        deny.cancelButton(false);
        ((javafx.scene.control.CheckBox) wide.node).setFocusTraversable(false);
        // The custom-span box is out of the Tab order too, so it cannot take INITIAL focus. It still
        // focuses on a click, which is the only time anyone wants to type in it.
        ((TextField) custom.node).setFocusTraversable(false);
        // F3 ticks and unticks breadth, so the whole window answers from the keyboard.
        ((javafx.scene.control.CheckBox) wide.node).setText("F3 · Apply to every item THIS ONE COMMAND touches");

        var spans = flowPane().attr(p -> {
            p.setHgap(6);
            p.setVgap(6);
        });
        choices.forEach(spans::add);

        // Nothing is grantable until the passphrase proves a person is here. A click can be synthesised
        // by anything running as this user; a passphrase cannot.
        //
        // Modena expresses "disabled" as 40% opacity and nothing else, which on the pale red tint of the
        // irreversible tier leaves the span buttons at roughly 2:1 against their background — unreadable,
        // on the one tier where knowing what you are about to enable matters most. 62% still reads as
        // inactive and can still be read.
        Consumer<Boolean> gate = open -> {
            for (var c : choices) {
                ((Button) c.node).setDisable(!open);
                c.node.setStyle(open ? "" : "-fx-opacity: 0.62;");
            }
            ((TextField) custom.node).setDisable(!open);
            // Widening is a grant like any other, so it sits behind the same passphrase.
            ((javafx.scene.control.CheckBox) wide.node).setDisable(!open);
        };
        gate.accept(!locked);

        phrase.attr(f -> {
            f.setPromptText("wallet passphrase, then Enter");
            f.setPrefColumnCount(16);
            f.setOnAction(e -> {
                var typed = f.getText().toCharArray();
                try {
                    if (!verify.test(typed)) {
                        f.clear();
                        // Names the likely mistake. This is the moment someone discovers they were unsure
                        // which secret was wanted, and "wrong, try again" does not resolve that.
                        gateNote.text("That is not this wallet's passphrase. Cleared — type it again."
                                + " (It is the passphrase that unlocks the wallet, not the request code above.)");
                        gateNote.style("-fx-font-size: 11px; -fx-text-fill: " + RED + ";");
                        return;
                    }
                    f.setDisable(true);
                    gateNote.text("Confirmed. Choose how long this stands.");
                    gateNote.style("-fx-font-size: 11px; -fx-text-fill: " + GREEN + ";");
                    gate.accept(true);
                    settleUntil.set(System.currentTimeMillis() + SETTLE_MS);
                    ((Button) choices.getFirst().node).requestFocus();
                } finally {
                    Arrays.fill(typed, '\0');
                }
            });
        });

        custom.attr(f -> {
            f.setPromptText("or type a span, up to " + Span.describe(cap));
            f.setPrefColumnCount(16);
            f.setOnAction(e -> {
                var minutes = Span.parse(f.getText());
                if (minutes == null) {
                    f.setStyle("-fx-border-color: " + RED + ";");
                    f.setPromptText("not a span — try 45m, 12h, 3d");
                    return;
                }
                if (minutes == 0 || minutes > cap) {
                    f.setStyle("-fx-border-color: " + RED + ";");
                    f.clear();
                    f.setPromptText(badge(ask.tier()) + " grants stop at " + Span.describe(cap));
                    return;
                }
                finish.accept(ApprovalAnswer.forMinutes(minutes, danger ? settings.destructiveOps : -1,
                        breadth(wide)));
            });
        });

        var band = label(badge(ask.tier())).attr(l -> l.setMaxWidth(Double.MAX_VALUE))
                .style("-fx-background-color: " + accent + "; -fx-text-fill: white; -fx-font-size: 13px;"
                        + " -fx-font-weight: bold; -fx-padding: 8 16 8 16;");

        // The big code identifies which command is asking. On the passphrase tiers its caption says it is
        // not what goes in the box, because big code then box invites typing the one into the other.
        var content = vbox().spacing(9).padding(18).nodes(
                label(ask.correlationCode() == null ? "" : ask.correlationCode())
                        .style("-fx-font-size: 34px; -fx-font-weight: bold; -fx-text-fill: " + accent
                                + "; -fx-font-family: 'Consolas';"),
                label(locked
                        ? "request code — it identifies which command is asking. NOT what goes in the box below."
                        : "request code — identifies which command is asking")
                        .wrapText(true).style(locked
                        ? "-fx-font-size: 11px; -fx-text-fill: " + WARN + ";" : NOTE),
                label(ask.headline()).style("-fx-font-size: 16px; -fx-font-weight: bold;").wrapText(true),

                label(blanket ? "EVERY document, present and future"
                                : unresolved ? "unidentified resource" : ask.resource().label())
                        .style("-fx-font-size: 15px; -fx-font-weight: bold;"
                                + (unresolved || blanket ? " -fx-text-fill: " + WARN + ";" : "")).wrapText(true),
                label(blanket ? text(ask.resourceKind())
                                : text(ask.resourceKind()) + "  ·  " + text(ask.resource().id()))
                        .style("-fx-font-size: 11px; -fx-font-family: 'Consolas'; -fx-text-fill: "
                                + (unresolved || blanket ? WARN : MUTED) + ";").wrapText(true),

                label("account: " + text(ask.account()) + "    profile: " + text(ask.profile())
                        + "    pid: " + ask.pid()).style(NOTE),
                label("session: " + text(ask.session())).style(NOTE)
                        .wrapText(true));

        content.nodes(caller(ask), separator());

        if (locked) {
            content.nodes(
                    label(danger
                            ? "This cannot be undone, so the passphrase is required before it can be approved."
                            : "This covers documents nobody has named, so the passphrase is required before"
                              + " it can be approved.")
                            .style("-fx-font-size: 12px; -fx-font-weight: bold;").wrapText(true),
                    // Says which secret, because there are two plausible answers on screen and only one is
                    // right. The field's own prompt text says "passphrase" but disappears the moment a
                    // character is typed, which is exactly when someone realises they are unsure.
                    label("Type the WALLET PASSPHRASE — the one that unlocks this wallet. Not the code above.")
                            .wrapText(true).style(NOTE),
                    hbox().spacing(8).nodes(phrase),
                    gateNote.wrapText(true));
        }

        // Breadth is not a question a blanket grant can be asked. It already covers every document, so
        // offering a checkbox that widens it, and a hint pointing at the command being run right now,
        // would be two pieces of furniture that do nothing — and a control that does nothing is read as
        // a control that does something.
        if (!blanket) content.nodes(
                // Above the buttons, deliberately. It changes what every one of them does, and a
                // modifier placed after the thing it modifies is read only by people who did not need
                // it. This was originally below and the window height was not raised to fit it, so it
                // sat off the bottom edge and 31 approvals were answered one at a time without it ever
                // being seen — which is the exact complaint it exists to answer.
                wide.style("-fx-font-weight: bold;"),
                label("Off: this answer covers this one item — almost always what you want. On: it covers"
                        + " everything this command touches, for one action that spans many files (a folder"
                        + " move, a bulk share, creating a batch) where the buttons below would"
                        + " otherwise be answered once per file. Tied to "
                        + (ask.session() == null || ask.session().isBlank() ? "this run" : ask.session())
                        + " — so it disappears when the command ends"
                        + (danger ? ", and still stops at " + settings.destructiveOps + " operations." : "."))
                        .wrapText(true).style(NOTE),
                // The answer to "stop asking me" is not on this window, and leaving that unsaid is what
                // turned one question into six. It is deliberately not a button here either: a way out of
                // being interrupted, offered at the moment of the interruption, is how a bounded decision
                // becomes a reflex. Breadth here cannot outlive the command by design; a grant that spans
                // commands is a thing you go and do on purpose, with the passphrase in front of it.
                label("To stop being asked across many commands: the tray icon → \"Open access for a"
                        + " while…\", or uskoag-walletcli policy quiet --tier read (or write). Both want the"
                        + " passphrase, both expire, and both show in policy list where you can revoke them.")
                        .wrapText(true).style(NOTE));

        content.nodes(
                separator(),
                label(danger
                        ? "Approve for — capped at " + settings.destructiveOps + " operations and at "
                          + Span.describe(cap) + ", whichever comes first:"
                        : "Approve for — " + badge(ask.tier()) + " permissions stop at "
                          + Span.describe(cap) + ", and extending later is one click:")
                        .style("-fx-font-size: 12px; -fx-font-weight: bold;").wrapText(true),
                spans,
                hbox().spacing(8).nodes(custom, deny),
                // This line described the window as it was before the F-key redesign, and every key it
                // named had already been deliberately disconnected: nothing here is focusable, so Tab
                // moves nothing and Enter takes nothing. A status bar that names keys which do nothing
                // teaches the wrong reflex on the one window where the reflex matters.
                // wrapText, because it did not fit and was being cut at "…remembers not…". A line whose
                // whole job is to teach the keys was ending in an ellipsis on the one window where the
                // keys are the entire interface. Costs a second line only when the width demands it.
                label("Every answer is one function key   ·   Esc denies"
                        + "   ·   F4 (\"Once\") allows just this call and remembers nothing")
                        .wrapText(true).style(NOTE),
                closing.style(ASIDE));

        var root = vbox().nodes(band, content)
                .style(Ui.INK + " -fx-background-color: " + tint(ask.tier()) + ";");

        deny.attr(b -> b.setOnAction(e -> finish.accept(ApprovalAnswer.deny())));

        // Sized to its content inside the wallet's frame, held to the screen's height (Look.fit). The
        // surface is the tier tint, so the colour is judged before any word is read.
        var sc = Look.fit(frame, (javafx.scene.layout.Region) root.node, WIDTH, tint(ask.tier()));
        frame.onClose(() -> finish.accept(ApprovalAnswer.deny()));

        // Keystrokes already in flight when the window appeared are not answers to a question nobody
        // had read yet, so for the first SETTLE_MS every key is swallowed. Esc then denies, F2 denies,
        // F3 ticks breadth, and every other answer is its own function key, once.
        actions.put(KeyCode.F2, () -> finish.accept(ApprovalAnswer.deny()));
        keyNodes.put(KeyCode.F2, deny.node);

        sc.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (System.currentTimeMillis() < settleUntil.get()) {
                e.consume();
                return;
            }
            // A focused text field (the passphrase, the custom span) keeps its own editing keys, so a typo
            // can be corrected; function keys and Esc still come here, so Deny is always reachable.
            var focused = sc.getFocusOwner();
            var editing = focused instanceof javafx.scene.control.TextInputControl t
                    && !t.isDisabled() && t.isEditable();
            if (editing && !e.getCode().isFunctionKey() && e.getCode() != KeyCode.ESCAPE) return;
            e.consume();

            var code = e.getCode() == KeyCode.ESCAPE ? KeyCode.F2 : e.getCode();
            if (code == KeyCode.F3) {
                var box = (javafx.scene.control.CheckBox) wide.node;
                if (!box.isDisabled() && !blanket) box.setSelected(!box.isSelected());
                return;
            }
            var act = actions.get(code);
            if (act == null) return;
            // The passphrase gate holds against the keyboard too: a disabled button's action is not run
            // by its key. Deny is never disabled, so refusing stays possible at any moment.
            var target = keyNodes.get(code);
            if (target != null && target.isDisabled()) {
                gateNote.text("The passphrase is required before this can be approved. Type it above.");
                gateNote.style("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + RED + ";");
                return;
            }
            act.run();
        });

        Ui.toFront(stage);
        // Focus goes to the passphrase box when there is one, where a stray key is a harmless character.
        // Otherwise to DENY: an accidental refusal pauses a run and says how to resume, an accidental grant
        // is a standing permission nobody knows exists.
        (locked ? phrase.node : deny.node).requestFocus();
        countdown(stage, closing);
        return stage;
    }

    /**
     * Quiet, in the corner, and honest: the window does close on its own, and a dialog that vanishes with
     * no warning reads as a crash. Not loud, because the deadline is not the point of the dialog.
     */
    private static void countdown(Stage stage, luvjfx.FxLabel closing) {
        var left = new java.util.concurrent.atomic.AtomicInteger(WAIT_SECONDS);
        var clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            var n = left.decrementAndGet();
            closing.text(n <= 0 ? "closing…"
                    : "refused automatically in " + n / 60 + ":" + String.format("%02d", n % 60));
        }));
        clock.setCycleCount(Animation.INDEFINITE);
        clock.play();
        stage.setOnHidden(e -> clock.stop());
        closing.text("refused automatically in " + WAIT_SECONDS / 60 + ":"
                + String.format("%02d", WAIT_SECONDS % 60));
    }

    /**
     * Counts before clocks stays true on the irreversible tier: a span bounds a person's sitting and does
     * nothing to bound a loop, so every irreversible grant carries an operation cap as well.
     */
    private static ApprovalAnswer grant(Span span, boolean danger, int ops, Match match) {
        return ApprovalAnswer.forMinutes(span.minutes, danger ? ops : -1, match);
    }

    /** Ticked means every resource of this api, and {@link uskoag.wallet.daemon.PolicyEngine} then ties it to the run. */
    /** The button text for a key, so the "press again" line names the thing rather than the key. */
    private static String labelFor(java.util.List<luvjfx.FxButton> choices, KeyCode code) {
        var prefix = code.getName() + " · ";
        for (var c : choices) {
            var t = ((Button) c.node).getText();
            if (t != null && t.startsWith(prefix)) return t.substring(prefix.length());
        }
        return "approve";
    }

    /**
     * Where the command was run, and what the command was. The two rows he asked for, and the reason is
     * his: <i>"running in which directory — so I know which project, this single thing makes approval
     * easy"</i>, and, of the command, <i>"if you see the command even I can tell what is being
     * planned/executed"</i> — the alternative being to elevate the restriction on a batch merely because
     * it could not be seen.
     *
     * <p>The directory is the prominent one and is the only line here in ordinary ink rather than as an
     * aside, because it is the fact the decision is actually made on. The command sits in its own scroll
     * pane because a real batch invocation is longer than this window is wide, and it is height-bounded so
     * that a long one costs three lines rather than a screenful — see {@link #COMMAND_MAX_H}.
     *
     * <p>Nothing in here is focusable, which is not a detail: this window's whole keyboard design rests on
     * there being nothing for Enter, Space or Tab to reach.
     */
    private static luvjfx.FxVBox caller(ApprovalAsk ask) {
        var c = ask.callerOrUnknown();
        var box = vbox().spacing(4);

        box.nodes(label(c.workingDir() == null ? "directory not stated" : "in  " + c.workingDir())
                .wrapText(false)
                .attr(l -> l.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS))
                .style("-fx-font-size: 12px; -fx-font-family: 'Consolas';"
                        + (c.workingDir() == null ? " -fx-text-fill: " + WARN + ";" : "")));

        var line = label(c.commandLine() == null ? "command not stated" : c.commandLine())
                .wrapText(true)
                .style("-fx-font-size: 11px; -fx-font-family: 'Consolas'; -fx-padding: 4 6 4 6;"
                        + " -fx-text-fill: " + (c.commandLine() == null ? WARN : MUTED) + ";");

        // A white inset so the command reads as a quoted thing rather than as more prose, and `-fx-background`
        // named explicitly rather than left transparent — Modena's ladder() derives the default text colour
        // from it, and `transparent` reads as black and turns every unstyled descendant white. That cost a
        // whole dialog's legibility once already; see Ui.fitToContent.
        var scroller = new javafx.scene.control.ScrollPane(line.node);
        scroller.setFitToWidth(true);
        scroller.setFocusTraversable(false);
        scroller.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setMaxHeight(COMMAND_MAX_H);

        /*
         * The height is driven off the label's REAL laid-out height, and a maxHeight alone was not enough.
         *
         * A ScrollPane derives its preferred height from its content's preferred height, and a wrapped
         * Label asked for its preferred height reports the height of ONE line — the wrap only happens once
         * something has fixed its width, which here is fitToWidth at layout time. So the box came out two
         * lines tall with a scroll bar for a command that had four lines and room for all of them, while
         * maxHeight sat well above and never applied to anything. Measured, not reasoned: the preview
         * harness photographed exactly that.
         *
         * heightProperty is the answer because it is the only number in this that has been through a
         * layout pass. It converges in one step and cannot flap: a bar appearing narrows the viewport,
         * which can only make the text taller, which can only keep the bar.
         */
        line.node.heightProperty().addListener((o, was, now) ->
                scroller.setPrefHeight(Math.min(now.doubleValue() + 2, COMMAND_MAX_H)));
        scroller.setStyle("-fx-background: #ffffff; -fx-background-color: #ffffff;"
                + " -fx-padding: 0; -fx-background-insets: 0;"
                + " -fx-border-color: #e2dccf; -fx-border-width: 1;");
        box.add(scroller);

        /*
         * An undeclared caller is TOLD ON, not squeezed. His ruling, and it is the right one.
         *
         * Restricting one would look like a security control and would not be one: the declaration is
         * self-reported and unverifiable — the same objection that ended the per-tool contract idea in
         * session 05 — so anything that wanted a wider grant would simply declare a plausible command and
         * get it. What would be left is friction applied to honest tools only, plus a belief that the
         * wallet can tell callers apart. It cannot. This line exists so a person can judge, which is the
         * only thing the facts above are good for.
         */
        if (!c.declared()) {
            box.nodes(label(c.commandLine() == null
                    ? "This caller did not say what it is running. Nothing is being withheld from it on that"
                      + " account — the wallet cannot verify such a claim anyway — but there is less here to"
                      + " judge it by than usual."
                    : "This caller did not state its command; the line above was recovered from the JVM and"
                      + " may be partial.")
                    .wrapText(true).style("-fx-font-size: 11px; -fx-text-fill: " + WARN + ";"));
        }
        return box;
    }

    private static Match breadth(luvjfx.FxCheckBox wide) {
        return ((javafx.scene.control.CheckBox) wide.node).isSelected() ? Match.ANY : Match.EXACT;
    }

    private static String accent(Tier tier) {
        return switch (tier) {
            case DESTRUCTIVE -> RED;
            case MUTATE -> AMBER;
            case READ -> GREEN;
        };
    }

    /** Pale enough that the default dark text stays fully legible; the band carries the saturation. */
    private static String tint(Tier tier) {
        return switch (tier) {
            case DESTRUCTIVE -> "#fdeaea";
            case MUTATE -> "#fff4e5";
            case READ -> "#eaf5ec";
        };
    }

    private static String badge(Tier tier) {
        return switch (tier) {
            case DESTRUCTIVE -> "IRREVERSIBLE";
            case MUTATE -> "CHANGE";
            case READ -> "READ";
        };
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }
}

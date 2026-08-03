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
 * <p><b>Every answer is one function key, pressed twice.</b> F2 F2 refuses, F4 F4 grants once, F6/F7/F8
 * grant a span. Nothing on this window is focusable and nothing is a default button, so Enter, Space and
 * Tab do nothing at all; the mouse still works for anyone who prefers it.
 *
 * <p>The two halves solve two different problems and neither is sufficient alone. <b>Function keys</b>
 * are keys that composing text never emits, so a window that raised itself over someone mid-sentence
 * cannot be answered by the sentence — which is precisely what happened before this: the widest span on
 * offer was the scene's default button and also held focus, so the next Enter or space of a paragraph
 * being typed granted a seven-day permission, reported as "the popup came and it just selected something
 * ... i have no clue". <b>Pressing twice</b> then covers the remaining case, a stray function key, and
 * carries a minimum gap so a held key cannot repeat its way through both presses, plus a short expiry so
 * an old arm cannot be completed minutes later. A first press says on screen what a second will do.
 *
 * <p>Every key that is <em>not</em> a function key goes into the confirmation code, which is the only
 * thing ordinary typing can reach and which grants nothing by itself. It exists for the breadth
 * checkbox: that is the one answer without a bound, since every other choice is limited by a span and a
 * tier ceiling while breadth means "and everything else this run touches". Four characters, case
 * insensitive, different every time and belonging to this request — so typing it is evidence the window
 * was read. Friction proportional to blast radius.
 *
 * <p>The irreversible tier is different in three ways, all of them because it cannot be undone: the
 * passphrase is asked for again, the spans stop at one day, and no code is asked for — the passphrase
 * already stands in front of everything, and demanding two proofs of one intent is how a control gets
 * resented and worked around.
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
    private static final long SETTLE_MS = 1200;

    /**
     * The shortest gap that counts as two deliberate presses rather than one.
     *
     * <p>Without a floor, "press it twice" is satisfied by holding the key down: the OS repeats it, and
     * a held key is the single most likely thing to arrive from someone who was typing. With a floor,
     * repeat cannot arm and confirm — the second event has to be a separate physical press.
     */
    private static final long REARM_MIN_MS = 150;

    /** How long an armed choice waits before forgetting itself, so an old arm cannot be completed later. */
    private static final long ARM_WINDOW_MS = 3000;

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
        var stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setAlwaysOnTop(true);
        stage.setTitle(uskoag.wallet.wire.Brand.titled("approval"));

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

        /*
         * Breadth is the one answer that costs more than a keystroke, because it is the one answer that
         * is open-ended: every other choice is bounded by a span and a tier ceiling, while this converts
         * a single question into "and everything else this run touches". Friction proportional to blast
         * radius.
         *
         * What it costs is typing the code already on screen — four characters, case-insensitive.
         * Cheap, and it cannot be produced by not-reading: the code is different every time and belongs
         * to this request, so typing it is evidence that this window was looked at. It is also where
         * every non-function key goes, which is what makes the routing safe: the keys prose is made of
         * can only ever reach a box that grants nothing on its own.
         *
         * On the irreversible tier the passphrase already stands in front of all of this, and asking for
         * two proofs of the same intent is how a control gets resented and worked around. So there the
         * code is not asked for at all.
         */
        var wanted = locked || ask.correlationCode() == null ? null
                : ask.correlationCode().trim().toUpperCase(java.util.Locale.ROOT);
        var codeEntry = label("");
        var codeBuf = new StringBuilder();

        Consumer<ApprovalAnswer> finish = a -> {
            answer.accept(a);
            stage.close();
        };

        // Re-armable, because there are two moments when keys arrive that were not aimed at a choice:
        // when the window first appears over whatever someone was doing, and immediately after the
        // passphrase is submitted, when focus moves onto the span buttons and a repeated or held Enter
        // would land on one.
        var settleUntil = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() + SETTLE_MS);

        // Every answer is one function key pressed twice. The keys are what makes it safe and the
        // doubling is what makes it deliberate, and they solve different halves of the same problem:
        // an F-key is a key that composing text never emits, so nothing typed at a window that stole
        // focus can reach a choice at all; pressing it twice means even a stray F-key is not an answer.
        // Neither grant nor deny is reachable by accident, and both cost one key.
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
        // focuses on a click, which is the only time anyone wants to type in it. If it held focus by
        // default, the filter above would route typing into it instead of into the confirmation code, and
        // the code — verified working — would silently stop filling.
        ((TextField) custom.node).setFocusTraversable(false);

        // Where a code is asked for, the checkbox shows breadth rather than setting it: typing the code
        // is the only way to turn it on. Left clickable it would be a one-click route around the very
        // friction it was given, and a control with a documented cost and an undocumented free path is
        // worse than one with no cost at all, because only the second is honest about what it is.
        //
        // On the irreversible tier there is no code — the passphrase already stands in front of
        // everything — so there the box stays a real checkbox, enabled once the passphrase verifies.
        if (wanted != null) ((javafx.scene.control.CheckBox) wide.node).setMouseTransparent(true);

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
                                + " (It is the passphrase that unlocks the wallet, not the code above.)");
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

        /*
         * The big code needs a caption, and on the passphrase tiers it needs a disclaimer.
         *
         * On every tier below this one, the code shown here IS the thing you type — `wanted` is literally
         * this string uppercased, and typing it is what turns breadth on. So the reflex the window itself
         * teaches is "big code at the top, type it in the box". On the passphrase tiers there is no code
         * to type and the box wants the wallet passphrase, but the layout is otherwise identical: big code,
         * then a box. Someone who has answered a few of the ordinary ones will type the code into the
         * passphrase field, be told it is wrong, and have the box cleared under them.
         *
         * Cheap to fix and worth fixing, because the cost of the confusion is paid on the one tier where
         * a wrong entry is most expensive.
         */
        var content = vbox().spacing(9).padding(18).nodes(
                label(ask.correlationCode() == null ? "" : ask.correlationCode())
                        .style("-fx-font-size: 34px; -fx-font-weight: bold; -fx-text-fill: " + accent
                                + "; -fx-font-family: 'Consolas';"),
                label(locked
                        ? "request code — it identifies which command is asking. NOT what goes in the box below."
                        : "request code — identifies which command is asking, and typing it is what widens"
                          + " the answer")
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
                (wanted == null
                        ? label("Enabled by the passphrase above.")
                                .style(NOTE)
                        : hbox().spacing(8).nodes(
                                label("To turn it on, type the code " + wanted + " :")
                                        .style(NOTE),
                                codeEntry.style("-fx-font-family: monospace; -fx-font-size: 13px;"))),
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
                label("Every answer is one function key, pressed twice   ·   Esc arms a denial"
                        + "   ·   F4 F4 (\"Once\") allows just this call and remembers nothing")
                        .wrapText(true).style(NOTE),
                closing.style(ASIDE));

        var root = vbox().nodes(band, content)
                .style(Ui.INK + " -fx-background-color: " + tint(ask.tier()) + ";");

        deny.attr(b -> b.setOnAction(e -> finish.accept(ApprovalAnswer.deny())));

        /*
         * Sized to its content, then clamped to the screen. Not a hard-coded height, and not scrolling.
         *
         * Two fixed numbers were carried here for the same reason and both were wrong in both directions.
         * Too small and a control falls off the bottom invisibly — that happened to the breadth checkbox and
         * cost 31 approvals answered one at a time by someone who never saw it. So the numbers were raised;
         * now the tiers that show fewer lines (a blanket grant has no checkbox, no hint and no code) open
         * with a large empty panel. A constant cannot be right for a window whose content varies by tier, by
         * whether a name resolved, and by how long a document's title is.
         *
         * Ui.fitToContent owns all of it now, including the scroll pane and whether its bar is ever shown —
         * it is not, on any tier, on any ordinary screen. See the note there for why the first attempt at
         * measuring came out short and scrolled anyway.
         */
        // Constructed directly rather than through Fx.scene, which takes a luvjfx wrapper; the helper is
        // exactly `new Scene(root.node(), w, h)`, so nothing is lost. The height here is provisional and is
        // replaced by the measurement below. The fill matches the tier tint so that a frame in which the
        // content has not yet caught up with the window shows the window's own colour, not white.
        var sc = new javafx.scene.Scene(root.node, WIDTH, 400);
        sc.setFill(javafx.scene.paint.Color.web(tint(ask.tier())));

        // Keystrokes already in flight when the window appeared are not answers to a question nobody
        // had read yet. This window raises itself over whatever someone is doing, so the keys arriving
        // in the first moments belong to the sentence they were typing, not to the dialog — and the
        // whole value of an approval is that it was a decision. A filter, so it runs before any button
        // sees the event; ESC excepted, because refusing early is always safe and a run that is denied
        // pauses cleanly and can be re-approved.
        actions.put(KeyCode.F2, () -> finish.accept(ApprovalAnswer.deny()));
        keyNodes.put(KeyCode.F2, deny.node);

        // One filter owns the whole keyboard, so nothing downstream can act on a key we did not route.
        //
        // Function keys are answers, pressed twice. Everything else — the keys prose is actually made
        // of — goes into the confirmation code, which is the only thing typing can affect and which by
        // itself grants nothing. Enter, Space and Tab do nothing at all.
        var armed = new java.util.concurrent.atomic.AtomicReference<KeyCode>();
        var armedAt = new java.util.concurrent.atomic.AtomicLong();

        /*
         * The first press has to be visible on the button it aims at.
         *
         * It was announced only as a line of text — "Press F6 again to …" — which is the right words in the
         * wrong place: someone pressing F6 is looking at the F6 button, not at a status line further down
         * the window. So the first press still read as nothing happening, on a window whose whole design
         * assumes the first press is felt as deliberate.
         *
         * The glow DECAYS over exactly ARM_WINDOW_MS rather than switching off at the end of it, so it is
         * not merely "a key is armed" but a reading of how much of the window is left. When it has gone,
         * the next press starts over — which is what the code already did, invisibly.
         *
         * An effect and not a style string: styles here are set per button by the helper above, so mutating
         * and restoring them risks losing the tier colouring. setEffect(null) is an exact undo.
         */
        var glowingNode = new java.util.concurrent.atomic.AtomicReference<javafx.scene.Node>();
        var glowTimer = new javafx.animation.Timeline();
        var armNote = new java.util.concurrent.atomic.AtomicReference<String>();

        Runnable disarm = () -> {
            glowTimer.stop();
            var n = glowingNode.getAndSet(null);
            if (n != null) n.setEffect(null);
            armed.set(null);
            // The instruction goes when it stops being true. It used to stay on screen indefinitely,
            // telling someone to press a key again long after a second press would no longer count as one.
            var note = armNote.getAndSet(null);
            var shown = ((javafx.scene.control.Label) gateNote.node).getText();
            if (note != null && note.equals(shown)) gateNote.text("");
        };

        java.util.function.Consumer<KeyCode> arm = code -> {
            disarm.run();
            var n = keyNodes.get(code);
            if (n == null) return;
            var shadow = new javafx.scene.effect.DropShadow(
                    javafx.scene.effect.BlurType.GAUSSIAN, javafx.scene.paint.Color.web(AMBER), 24, 0.8, 0, 0);
            n.setEffect(shadow);
            glowingNode.set(n);
            glowTimer.getKeyFrames().setAll(
                    new javafx.animation.KeyFrame(javafx.util.Duration.ZERO,
                            new javafx.animation.KeyValue(shadow.radiusProperty(), 24.0),
                            new javafx.animation.KeyValue(shadow.spreadProperty(), 0.8)),
                    new javafx.animation.KeyFrame(javafx.util.Duration.millis(ARM_WINDOW_MS),
                            new javafx.animation.KeyValue(shadow.radiusProperty(), 0.0),
                            new javafx.animation.KeyValue(shadow.spreadProperty(), 0.0)));
            glowTimer.setOnFinished(x -> disarm.run());
            glowTimer.playFromStart();
        };
        sc.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            var now = System.currentTimeMillis();
            if (now < settleUntil.get()) {
                // The settle window owns every key including the text fields, because a keystroke that
                // arrived before anyone could read this window is not aimed at anything on it.
                e.consume();
                return;
            }

            /*
             * A focused text field keeps its own editing keys, and this is a correctness fix rather than
             * a convenience.
             *
             * This filter used to consume KEY_PRESSED unconditionally. Character INSERTION survived that,
             * because JavaFX inserts text on KEY_TYPED, which this filter never sees — so the passphrase
             * box looked like it worked. Everything that EDITS is KEY_PRESSED though: BACK_SPACE, DELETE,
             * the arrows, Home, End, select-all. All of it was swallowed before the field's own behaviour
             * could run. So on the one tier that demands a secret typed exactly, a single mistyped
             * character could not be corrected, and a wrong entry clears the box and says "type it again".
             *
             * It cost a real action: three consecutive attempts to share one document, 22:30:04, 22:31:12
             * and 22:31:43, all three ending in DENY, because there was no way to fix a typo and no way
             * forward. The audit shows it as three refusals, which reads as three decisions. It was one
             * dead backspace key.
             *
             * Nothing is given away by allowing this. A text field grants nothing by itself — that is the
             * premise the confirmation code already rests on — and function keys and ESC are still taken
             * by the filter, so Deny stays reachable from the keyboard while the passphrase is being
             * typed. The only focusable things here are the passphrase box and the custom-span field.
             */
            var focused = sc.getFocusOwner();
            var editing = focused instanceof javafx.scene.control.TextInputControl t
                    && !t.isDisabled() && t.isEditable();
            if (editing && !e.getCode().isFunctionKey() && e.getCode() != KeyCode.ESCAPE) return;

            e.consume();

            // Esc arms a refusal rather than performing one. The universal way out of a dialog stays
            // where everyone expects it, without being the one reflex key that can answer by itself.
            var code = e.getCode() == KeyCode.ESCAPE ? KeyCode.F2 : e.getCode();
            var act = actions.get(code);

            /*
             * The passphrase gate has to hold against the KEYBOARD too, and it did not.
             *
             * gate() implements the gate as setDisable(true) on every granting button, which stops the
             * mouse and stops the button's own key handling. It does not stop this filter, which calls the
             * action's Runnable DIRECTLY — the lambda, not the button. So on the irreversible tier F4 F4 or
             * F6 F6 answered the dialog with the passphrase box still empty, and the one control here that
             * distinguishes a person from a process was two keypresses from being irrelevant. Open since
             * the F-key redesign, because that redesign moved the real route to the keyboard and the gate
             * stayed behind on the widgets.
             *
             * The lesson is the recorded one, again: a ceiling only one route respects is a suggestion.
             * Deny is deliberately never disabled, so it passes this check and refusing stays possible at
             * any moment, which is the one answer that must never be gated.
             */
            var target = keyNodes.get(code);
            if (act != null && target != null && target.isDisabled()) {
                disarm.run();
                gateNote.text("The passphrase is required before this can be approved. Type it above.");
                gateNote.style("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + RED + ";");
                return;
            }

            if (act == null) {
                if (wanted == null) return;
                var t = e.getText();
                if (e.getCode() == KeyCode.BACK_SPACE) {
                    if (codeBuf.length() > 0) codeBuf.deleteCharAt(codeBuf.length() - 1);
                } else if (t != null && t.length() == 1 && Character.isLetterOrDigit(t.charAt(0))) {
                    if (codeBuf.length() < wanted.length()) codeBuf.append(t.toUpperCase(java.util.Locale.ROOT));
                } else {
                    return;     // Enter, Space, Tab, arrows: nothing. They are not answers to anything.
                }
                var match = codeBuf.toString().contentEquals(wanted);
                ((javafx.scene.control.CheckBox) wide.node).setSelected(match);
                codeEntry.text(codeBuf.isEmpty() ? "" : codeBuf.toString());
                codeEntry.style("-fx-font-family: monospace; -fx-font-size: 13px; -fx-text-fill: "
                        + (match ? GREEN : WARN) + ";");
                return;
            }
            if (armed.get() == code && now - armedAt.get() >= REARM_MIN_MS
                    && now - armedAt.get() <= ARM_WINDOW_MS) {
                disarm.run();
                act.run();
                return;
            }
            // Armed, not done. Shown on the button AND said in words, because a first press that appears to
            // do nothing reads as a dead keyboard and gets hammered — which is the behaviour this is
            // trying to prevent.
            // arm() disarms whatever was armed before, which clears `armed` — so it runs FIRST and the new
            // arming is recorded after it.
            arm.accept(code);
            armed.set(code);
            armedAt.set(now);
            var what = code == KeyCode.F2 ? "Deny" : labelFor(choices, code);
            var note = "Press " + code.getName() + " again to " + what + ".";
            armNote.set(note);
            gateNote.text(note);
            gateNote.style("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + AMBER + ";");
        });
        stage.setOnCloseRequest(e -> answer.accept(ApprovalAnswer.deny()));
        stage.setScene(sc);

        Ui.fitToContent(stage, root.node, WIDTH, tint(ask.tier()));

        Ui.toFront(stage);
        // Focus goes to the passphrase box when there is one — he asked for that, and a stray key there
        // is a character in a field, which is harmless. Otherwise it goes to DENY. Focus has to land
        // somewhere for keyboard use, and the only safe somewhere is the choice that costs nothing to
        // get wrong: an accidental refusal pauses a run and says how to resume, an accidental grant is
        // a standing permission nobody knows exists.
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

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

    private static final String RED = "#b71c1c", AMBER = "#e65100", GREEN = "#1b5e20", WARN = "#8d6e00";

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
        var unresolved = ask.resource().label() == null || ask.resource().label().isBlank();
        var cap = ask.tier().maxMinutes;
        var locked = danger && settings.destructiveNeedsPassphrase;

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
        var wide = checkBox("Apply to everything this run touches, not just this one");
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

        var once = button("F4 · Once");
        once.attr(b -> b.setOnAction(e -> finish.accept(ApprovalAnswer.once())));
        choices.add(once);
        actions.put(KeyCode.F4, () -> finish.accept(ApprovalAnswer.once()));

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
            if (key != null) actions.put(key, act);
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
        Runnable applyGate = () -> {
            var open = !locked;
            for (var c : choices) ((Button) c.node).setDisable(!open);
            ((TextField) custom.node).setDisable(!open);
            // Widening is a grant like any other, so it sits behind the same passphrase.
            ((javafx.scene.control.CheckBox) wide.node).setDisable(!open);
        };
        applyGate.run();

        phrase.attr(f -> {
            f.setPromptText("passphrase, then Enter");
            f.setPrefColumnCount(16);
            f.setOnAction(e -> {
                var typed = f.getText().toCharArray();
                try {
                    if (!verify.test(typed)) {
                        f.clear();
                        gateNote.text("That is not this wallet's passphrase. Cleared — type it again.");
                        gateNote.style("-fx-font-size: 11px; -fx-text-fill: " + RED + ";");
                        return;
                    }
                    f.setDisable(true);
                    gateNote.text("Confirmed. Choose how long this stands.");
                    gateNote.style("-fx-font-size: 11px; -fx-text-fill: " + GREEN + ";");
                    for (var c : choices) ((Button) c.node).setDisable(false);
                    ((TextField) custom.node).setDisable(false);
                    ((javafx.scene.control.CheckBox) wide.node).setDisable(false);
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

        var content = vbox().spacing(9).padding(18).nodes(
                label(ask.correlationCode() == null ? "" : ask.correlationCode())
                        .style("-fx-font-size: 34px; -fx-font-weight: bold; -fx-text-fill: " + accent
                                + "; -fx-font-family: 'Consolas';"),
                label(ask.headline()).style("-fx-font-size: 16px; -fx-font-weight: bold;").wrapText(true),

                label(unresolved ? "unidentified resource" : ask.resource().label())
                        .style("-fx-font-size: 15px; -fx-font-weight: bold;"
                                + (unresolved ? " -fx-text-fill: " + WARN + ";" : "")).wrapText(true),
                label(text(ask.resourceKind()) + "  ·  " + text(ask.resource().id()))
                        .style("-fx-font-size: 11px; -fx-font-family: 'Consolas'; -fx-text-fill: "
                                + (unresolved ? WARN : "#888") + ";").wrapText(true),

                label("account: " + text(ask.account()) + "    profile: " + text(ask.profile())
                        + "    pid: " + ask.pid()).style("-fx-font-size: 11px; -fx-text-fill: #666;"),
                label("session: " + text(ask.session())).style("-fx-font-size: 11px; -fx-text-fill: #666;")
                        .wrapText(true),
                label(text(ask.peerCommand())).wrapText(true).style("-fx-font-size: 10px; -fx-text-fill: #999;"),
                separator());

        if (locked) {
            content.nodes(
                    label("This cannot be undone, so the passphrase is required before it can be approved.")
                            .style("-fx-font-size: 12px; -fx-font-weight: bold;").wrapText(true),
                    hbox().spacing(8).nodes(phrase),
                    gateNote.wrapText(true));
        }

        content.nodes(
                // Above the buttons, deliberately. It changes what every one of them does, and a
                // modifier placed after the thing it modifies is read only by people who did not need
                // it. This was originally below and the window height was not raised to fit it, so it
                // sat off the bottom edge and 31 approvals were answered one at a time without it ever
                // being seen — which is the exact complaint it exists to answer.
                wide.style("-fx-font-weight: bold;"),
                (wanted == null
                        ? label("Enabled by the passphrase above.")
                                .style("-fx-font-size: 11px; -fx-text-fill: #666;")
                        : hbox().spacing(8).nodes(
                                label("To turn it on, type the code " + wanted + " :")
                                        .style("-fx-font-size: 11px; -fx-text-fill: #666;"),
                                codeEntry.style("-fx-font-family: monospace; -fx-font-size: 13px;"))),
                label("Off: this answer covers this one item — almost always what you want. On: it covers"
                        + " everything this run touches, for one action that spans many files (a folder"
                        + " move, a bulk share, creating a batch) where the buttons below would"
                        + " otherwise be answered once per file. Tied to this run"
                        + (ask.session() == null || ask.session().isBlank() ? "" : " — " + ask.session())
                        + " — so it disappears when the command ends"
                        + (danger ? ", and still stops at " + settings.destructiveOps + " operations." : "."))
                        .wrapText(true).style("-fx-font-size: 11px; -fx-text-fill: #666;"),
                separator(),
                label(danger
                        ? "Approve for — capped at " + settings.destructiveOps + " operations and at "
                          + Span.describe(cap) + ", whichever comes first:"
                        : "Approve for — " + badge(ask.tier()) + " permissions stop at "
                          + Span.describe(cap) + ", and extending later is one click:")
                        .style("-fx-font-size: 12px; -fx-font-weight: bold;").wrapText(true),
                spans,
                hbox().spacing(8).nodes(custom, deny),
                label("Tab moves   ·   Enter takes the highlighted button   ·   Esc denies"
                        + "   ·   \"Once\" allows just this call and remembers nothing")
                        .style("-fx-font-size: 11px; -fx-text-fill: #777;"),
                closing.style("-fx-font-size: 10px; -fx-text-fill: #999;"));

        var root = vbox().nodes(band, content)
                .style(Ui.INK + " -fx-background-color: " + tint(ask.tier()) + ";");

        deny.attr(b -> b.setOnAction(e -> finish.accept(ApprovalAnswer.deny())));

        // Tall enough for everything, and it must stay that way: this window has no scroll bar, so
        // anything that does not fit is not merely cramped, it is invisible and silently unusable.
        var sc = scene(root, 660, locked ? 810 : 700);

        // Keystrokes already in flight when the window appeared are not answers to a question nobody
        // had read yet. This window raises itself over whatever someone is doing, so the keys arriving
        // in the first moments belong to the sentence they were typing, not to the dialog — and the
        // whole value of an approval is that it was a decision. A filter, so it runs before any button
        // sees the event; ESC excepted, because refusing early is always safe and a run that is denied
        // pauses cleanly and can be re-approved.
        actions.put(KeyCode.F2, () -> finish.accept(ApprovalAnswer.deny()));

        // One filter owns the whole keyboard, so nothing downstream can act on a key we did not route.
        //
        // Function keys are answers, pressed twice. Everything else — the keys prose is actually made
        // of — goes into the confirmation code, which is the only thing typing can affect and which by
        // itself grants nothing. Enter, Space and Tab do nothing at all.
        var armed = new java.util.concurrent.atomic.AtomicReference<KeyCode>();
        var armedAt = new java.util.concurrent.atomic.AtomicLong();
        sc.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            e.consume();
            var now = System.currentTimeMillis();
            if (now < settleUntil.get()) return;

            // Esc arms a refusal rather than performing one. The universal way out of a dialog stays
            // where everyone expects it, without being the one reflex key that can answer by itself.
            var code = e.getCode() == KeyCode.ESCAPE ? KeyCode.F2 : e.getCode();
            var act = actions.get(code);

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
                armed.set(null);
                act.run();
                return;
            }
            // Armed, not done. Said on screen, because a first press that appears to do nothing reads as
            // a dead keyboard and gets hammered — which is the behaviour this is trying to prevent.
            armed.set(code);
            armedAt.set(now);
            var what = code == KeyCode.F2 ? "Deny" : labelFor(choices, code);
            gateNote.text("Press " + code.getName() + " again to " + what + ".");
            gateNote.style("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + AMBER + ";");
        });
        stage.setOnCloseRequest(e -> answer.accept(ApprovalAnswer.deny()));
        stage.setScene(sc);
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

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
 * <p>Each span is its own button, so a decision is one click rather than pick-then-confirm. There are
 * deliberately no keyboard shortcuts on those buttons: a single keypress that grants a month of access is
 * too cheap for what it does, and cheap is how a control becomes a reflex. Tab and Enter still work,
 * which requires having looked at where the focus is.
 *
 * <p>The irreversible tier is different in three ways, all of them because it cannot be undone: the
 * passphrase is asked for again, the spans stop at one day, and Enter lands on "Once".
 */
public final class ApprovalWindow {

    private static final String RED = "#b71c1c", AMBER = "#e65100", GREEN = "#1b5e20", WARN = "#8d6e00";

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

        var deny = button("Deny  (Esc)").cancelButton(true);
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

        Consumer<ApprovalAnswer> finish = a -> {
            answer.accept(a);
            stage.close();
        };

        var once = button("Once");
        once.attr(b -> b.setOnAction(e -> finish.accept(ApprovalAnswer.once())));
        choices.add(once);

        luvjfx.FxButton defaultChoice = once;
        for (var span : Span.values()) {
            // Spans past this tier's ceiling are not offered at all, rather than offered and then silently
            // clamped: a button that does not do what it says is its own defect. "Forever" is therefore
            // never on screen, for any tier.
            if (!ask.tier().allows(span)) continue;
            var b = button(span.label);
            b.attr(x -> x.setOnAction(e -> finish.accept(
                    grant(span, danger, settings.destructiveOps, breadth(wide)))));
            choices.add(b);
            // Enter lands on the widest span this tier permits, which is bounded by construction — except
            // on the irreversible tier, where it lands on "Once" and a longer grant has to be aimed at.
            if (!danger) defaultChoice = b;
        }
        defaultChoice.defaultButton(true);

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
        sc.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) finish.accept(ApprovalAnswer.deny());
        });
        stage.setOnCloseRequest(e -> answer.accept(ApprovalAnswer.deny()));
        stage.setScene(sc);
        Ui.toFront(stage);
        (locked ? phrase.node : defaultChoice.node).requestFocus();
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

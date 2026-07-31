package uskoag.wallet.ui;

import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Modality;
import javafx.stage.Stage;
import uskoag.wallet.wire.ApprovalAnswer;
import uskoag.wallet.wire.ApprovalAsk;
import uskoag.wallet.wire.Match;
import uskoag.wallet.wire.Span;
import uskoag.wallet.wire.Tier;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static luvjfx.Fx.button;
import static luvjfx.Fx.flowPane;
import static luvjfx.Fx.hbox;
import static luvjfx.Fx.label;
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
 * correlation code is in large type for the same reason: with several agent runs in flight, "which one
 * is asking" is the only question that matters, and the client printed the same four characters to its
 * own stderr.
 *
 * <p>Colour tracks elevation, since that is the thing you judge before reading any words: green to
 * read, amber to change, red for anything that cannot be undone.
 *
 * <p>Each duration is its own button, so a decision is one keystroke rather than pick-then-confirm.
 * Enter never means forever — the focused button is a bounded span, and on the irreversible tier it is
 * "Once" — because the default has to be the answer you would not regret giving without reading.
 */
public final class ApprovalWindow {

    private static final String RED = "#b71c1c", AMBER = "#e65100", GREEN = "#1b5e20", WARN = "#8d6e00";

    /** Enter lands here: bounded, useful, and never the widest thing on offer. */
    private static final Span DEFAULT_SPAN = Span.MONTH;

    /**
     * Deliberately under the client's 300s read timeout in {@code GrantInitializer}. The wallet has to
     * give up first, or the call dies while this window is still open and a later click grants a rule
     * for a request that no longer exists.
     */
    private static final int WAIT_SECONDS = 240;

    private ApprovalWindow() {
    }

    public static ApprovalAnswer ask(ApprovalAsk ask, int defaultOps, int defaultMinutes) {
        var out = new AtomicReference<>(ApprovalAnswer.deny());
        var done = new CountDownLatch(1);
        var stage = new AtomicReference<Stage>();

        javafx.application.Platform.runLater(() -> stage.set(build(ask, defaultOps, answer -> {
            out.set(answer);
            done.countDown();
        })));
        try {
            if (!done.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                // The client is gone by now, so leaving this on screen would let a click minutes later
                // write a rule for a request that already failed. Close it and refuse.
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

    private static Stage build(ApprovalAsk ask, int defaultOps, Consumer<ApprovalAnswer> answer) {
        var stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setAlwaysOnTop(true);
        stage.setTitle("uskoag wallet — approval");

        var danger = ask.tier() == Tier.DESTRUCTIVE;
        var accent = accent(ask.tier());
        var unresolved = ask.resource().label() == null || ask.resource().label().isBlank();

        var deny = button("Deny  (Esc)").cancelButton(true);
        var custom = textField();
        var choices = new ArrayList<luvjfx.FxButton>();

        Consumer<ApprovalAnswer> finish = a -> {
            answer.accept(a);
            stage.close();
        };

        // "Once" is not a span: it writes no rule at all, so the next call asks again.
        var once = button("1  Once");
        once.attr(b -> b.setOnAction(e -> finish.accept(ApprovalAnswer.once())));
        choices.add(once);

        for (var span : Span.values()) {
            var key = choices.size() + 1;
            var b = button(key + "  " + span.label);
            b.attr(x -> x.setOnAction(e -> finish.accept(grant(span, danger, defaultOps))));
            if (span == DEFAULT_SPAN && !danger) b.defaultButton(true);
            choices.add(b);
        }
        if (danger) once.defaultButton(true);

        var spans = flowPane().attr(p -> {
            p.setHgap(6);
            p.setVgap(6);
        });
        choices.forEach(spans::add);

        custom.attr(f -> {
            f.setPromptText("or type a span:  45m  12h  10d  3w  6mo  2y");
            f.setPrefColumnCount(18);
            f.setOnAction(e -> {
                var minutes = Span.parse(f.getText());
                if (minutes == null) {
                    f.setStyle("-fx-border-color: " + RED + ";");
                    f.setPromptText("not a span — try 12h, 10d, 3w, 6mo");
                    return;
                }
                finish.accept(ApprovalAnswer.forMinutes(minutes, danger ? defaultOps : -1, Match.EXACT));
            });
        });

        // The elevation has to be legible before any word is read, so it is the surface itself: a solid
        // band across the top and a wash over the whole panel. Coloured text alone was too quiet — it
        // reads as decoration, and the point is that you should already know what kind of question this
        // is while your eyes are still travelling to the words.
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
                label(text(ask.peerCommand())).wrapText(true).style("-fx-font-size: 10px; -fx-text-fill: #999;"),
                separator(),

                label(danger
                        ? "Approve for — and every grant below is also capped at " + defaultOps
                          + " operations, whichever limit comes first:"
                        : "Approve for:").style("-fx-font-size: 12px; -fx-font-weight: bold;").wrapText(true),
                spans,
                hbox().spacing(8).nodes(custom, deny),
                label("1-8 choose a span   ·   type a span and press Enter   ·   Esc denies"
                        + "   ·   Tab moves").style("-fx-font-size: 11px; -fx-text-fill: #777;"),
                label("Unanswered for " + WAIT_SECONDS + "s this is refused and the window closes,"
                        + " because the caller will have given up by then.")
                        .style("-fx-font-size: 10px; -fx-text-fill: #999;").wrapText(true));

        var root = vbox().nodes(band, content)
                .style(Ui.INK + " -fx-background-color: " + tint(ask.tier()) + ";");

        deny.attr(b -> b.setOnAction(e -> finish.accept(ApprovalAnswer.deny())));

        var sc = scene(root, 640, 560);
        sc.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                finish.accept(ApprovalAnswer.deny());
                return;
            }
            // Digits are shortcuts only while the span box does not have the caret, or "10d" would fire
            // the first button before the second character arrived.
            if (((TextField) custom.node).isFocused()) return;
            var pick = digit(e.getCode());
            if (pick >= 1 && pick <= choices.size()) choices.get(pick - 1).node.fire();
        });
        stage.setOnCloseRequest(e -> answer.accept(ApprovalAnswer.deny()));
        stage.setScene(sc);
        Ui.toFront(stage);
        (danger ? once : choices.get(DEFAULT_SPAN.ordinal() + 1)).node.requestFocus();
        return stage;
    }

    /**
     * Counts before clocks stays true on the irreversible tier: a span bounds a person's sitting and
     * does nothing to bound a loop, so every irreversible grant carries an operation cap as well.
     * Read and change keep an unlimited count, which is what they had before spans existed.
     */
    private static ApprovalAnswer grant(Span span, boolean danger, int ops) {
        return ApprovalAnswer.forMinutes(span.minutes, danger ? ops : -1, Match.EXACT);
    }

    private static String accent(Tier tier) {
        return switch (tier) {
            case DESTRUCTIVE -> RED;
            case MUTATE -> AMBER;
            case READ -> GREEN;
        };
    }

    /** Pale enough that the default dark text stays fully legible on it; the band carries the saturation. */
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

    private static int digit(KeyCode code) {
        var name = code.getName();
        return name.length() == 1 && Character.isDigit(name.charAt(0)) ? name.charAt(0) - '0' : -1;
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }
}

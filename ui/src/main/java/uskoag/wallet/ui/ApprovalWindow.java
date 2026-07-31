package uskoag.wallet.ui;

import javafx.stage.Modality;
import javafx.stage.Stage;
import uskoag.wallet.wire.ApprovalAnswer;
import uskoag.wallet.wire.ApprovalAsk;
import uskoag.wallet.wire.Match;
import uskoag.wallet.wire.Tier;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static luvjfx.Fx.button;
import static luvjfx.Fx.hbox;
import static luvjfx.Fx.label;
import static luvjfx.Fx.scene;
import static luvjfx.Fx.vbox;

/**
 * The dialog that has to be readable in two seconds.
 *
 * <p>It names the actual operation rather than asking for "write access", because a prompt you cannot
 * evaluate is a prompt you will click through. The correlation code is in large type for the same
 * reason: with several agent runs in flight, "which one is asking" is the only question that matters,
 * and the client printed the same four characters to its own stderr.
 */
public final class ApprovalWindow {

    private static final String DANGER = "#b71c1c", CALM = "#1b5e20";

    private ApprovalWindow() {
    }

    public static ApprovalAnswer ask(ApprovalAsk ask, int defaultOps, int defaultMinutes) {
        var out = new AtomicReference<>(ApprovalAnswer.deny());
        var done = new CountDownLatch(1);

        javafx.application.Platform.runLater(() -> build(ask, defaultOps, defaultMinutes, answer -> {
            out.set(answer);
            done.countDown();
        }));
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out.get();
    }

    private static void build(ApprovalAsk ask, int defaultOps, int defaultMinutes,
                              java.util.function.Consumer<ApprovalAnswer> answer) {
        var stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setAlwaysOnTop(true);
        stage.setTitle("uskoag wallet — approval");

        var danger = ask.tier() == Tier.DESTRUCTIVE;
        var accent = danger ? DANGER : CALM;

        var deny = button("Deny  (Esc)").cancelButton(true);
        var once = button("Just once").defaultButton(danger);
        var remember = button(danger
                ? "Allow " + defaultOps + " ops for " + defaultMinutes + " min"
                : "Allow always").defaultButton(!danger);

        var root = vbox().spacing(10).padding(18).nodes(
                label(ask.correlationCode()).style("-fx-font-size: 34px; -fx-font-weight: bold; -fx-text-fill: "
                        + accent + "; -fx-font-family: 'Consolas';"),
                label(danger ? "IRREVERSIBLE" : "PERMISSION")
                        .style("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + accent + ";"),
                label(ask.headline()).style("-fx-font-size: 16px; -fx-font-weight: bold;"),
                label(ask.resource().display()).wrapText(true),
                label("account: " + ask.account() + "    profile: " + ask.profile()
                        + "    pid: " + ask.pid()).style("-fx-font-size: 11px; -fx-text-fill: #666;"),
                label(ask.peerCommand()).wrapText(true).style("-fx-font-size: 10px; -fx-text-fill: #999;"),
                hbox().spacing(8).nodes(deny, once, remember),
                label("Esc denies   ·   Tab moves   ·   Enter takes the highlighted button")
                        .style("-fx-font-size: 11px; -fx-text-fill: #777;"));

        java.util.function.Consumer<ApprovalAnswer> finish = a -> {
            answer.accept(a);
            stage.close();
        };
        deny.attr(b -> b.setOnAction(e -> finish.accept(ApprovalAnswer.deny())));
        once.attr(b -> b.setOnAction(e -> finish.accept(ApprovalAnswer.once())));
        remember.attr(b -> b.setOnAction(e -> finish.accept(danger
                ? ApprovalAnswer.forMinutes(defaultMinutes, defaultOps, Match.EXACT)
                : ApprovalAnswer.forever(Match.EXACT))));

        var sc = scene(root.style(Ui.INK), 560, 340);
        sc.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) finish.accept(ApprovalAnswer.deny());
        });
        stage.setOnCloseRequest(e -> answer.accept(ApprovalAnswer.deny()));
        stage.setScene(sc);
        Ui.toFront(stage);
        (danger ? once : remember).node.requestFocus();
    }
}

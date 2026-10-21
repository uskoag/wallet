package uskoag.wallet.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import leasegate.core.access.PassphraseGate;
import leasegate.frame.Arming;
import leasegate.frame.FKeys;
import leasegate.frame.Parts;
import leasegate.ui.ApprovalParts;
import leasegate.ui.PassphrasePrompt;
import uskoag.wallet.daemon.WalletSettings;
import uskoag.wallet.wire.ApprovalAnswer;
import uskoag.wallet.wire.ApprovalAsk;
import uskoag.wallet.wire.Match;
import uskoag.wallet.wire.Tier;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The confirmation for a rule someone asked for by typing a wallet command ({@code policy allow},
 * {@code policy quiet}, {@code policy extend}). The command already says what the rule is and how long it
 * stands, so there is no process to anchor at and nothing to choose: F2 confirms, Esc refuses, after the
 * window has settled. Wide or irreversible rules ask for the passphrase as well.
 */
final class PolicyConfirm {

    /** Under the client's own read timeout, so a late answer never writes a rule for a call already given up on. */
    static final int WAIT_SECONDS = 240;

    private PolicyConfirm() {
    }

    static ApprovalAnswer ask(ApprovalAsk ask, WalletSettings settings, PassphraseGate passphrase) {
        var out = new AtomicReference<>(ApprovalAnswer.deny());
        var done = new CountDownLatch(1);
        var frame = new AtomicReference<leasegate.frame.GateFrame>();
        Platform.runLater(() -> frame.set(show(ask, settings, passphrase, a -> {
            out.set(a);
            done.countDown();
        })));
        try {
            if (!done.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                out.set(ApprovalAnswer.timedOut(WAIT_SECONDS));
                Platform.runLater(() -> {
                    var f = frame.get();
                    if (f != null) f.stage.close();
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out.get();
    }

    private static leasegate.frame.GateFrame show(ApprovalAsk ask, WalletSettings settings, PassphraseGate passphrase,
                                                  java.util.function.Consumer<ApprovalAnswer> answer) {
        var danger = ask.tier() == Tier.DESTRUCTIVE;
        var needsPhrase = ask.requiresPassphrase() || (danger && settings.destructiveNeedsPassphrase);
        var frame = Look.frame("confirm a rule", false);
        var decided = new boolean[]{false};
        Runnable deny = () -> {
            if (decided[0]) return;
            decided[0] = true;
            answer.accept(ApprovalAnswer.deny());
            frame.stage.close();
        };
        Runnable approve = () -> {
            if (decided[0]) return;
            if (needsPhrase && !PassphrasePrompt.confirmOnce(passphrase, Look.BRAND, "confirm a wide rule",
                    danger ? "This cannot be undone. Type the passphrase to approve it."
                           : "This covers documents nobody has named. Type the passphrase to approve it.")) return;
            decided[0] = true;
            answer.accept(ApprovalAnswer.forMinutes(0, danger ? settings.destructiveOps : -1, Match.EXACT));
            frame.stage.close();
        };
        frame.onClose(deny);

        var keys = new FKeys()
                .bind(KeyCode.F2, "Approve", Look.COLOR, approve)
                .bind(KeyCode.ESCAPE, "Deny", "#777", deny);
        var line = Parts.line(" ", Parts.AMBER);
        var arming = new Arming(keys.bar.getChildren().stream().map(Button.class::cast).toList(), line);

        var caller = ask.callerOrUnknown();
        var box = new VBox(10,
                Parts.line(ask.headline(), "#222"),
                Parts.line(WalletRules.document(ask.resource()), "#222"),
                Parts.small(ask.resourceKind() == null ? "" : ask.resourceKind(), Parts.GREY),
                Parts.small("account: " + (ask.account() == null ? "every account" : ask.account())
                        + "    tier: " + WalletRules.word(ask.tier()), Parts.GREY),
                ApprovalParts.box("command", Optional.ofNullable(caller.commandLine())),
                ApprovalParts.box("working directory", Optional.ofNullable(caller.workingDir())),
                needsPhrase ? Parts.line("Approving needs the passphrase.", Parts.RED) : Parts.small(" ", Parts.GREY),
                keys.bar, line,
                Parts.small("F2 approves · Esc denies. Letters, digits, Space and Enter do nothing here.", Parts.GREY));
        box.setPadding(new Insets(16));
        box.setPrefWidth(600);
        var scene = frame.scene(box);
        arming.guard(scene);
        keys.install(scene);
        Ui.toFront(frame.stage);
        return frame;
    }
}

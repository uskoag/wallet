package uskoag.wallet.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import uskoag.wallet.daemon.WalletSettings;
import uskoag.wallet.wire.AncestryHop;
import uskoag.wallet.wire.ApprovalAnswer;
import uskoag.wallet.wire.ApprovalAsk;
import uskoag.wallet.wire.Match;
import uskoag.wallet.wire.Span;
import uskoag.wallet.wire.Tier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The dialog that has to be readable in two seconds, and answered once per run of work rather than once per
 * document.
 *
 * <p>It shows who is really asking: the tool that made the call, and above it the chain of processes that
 * started it. The person picks one of them to anchor the answer at, usually the agent, and the answer then
 * covers everything that process starts, on this account, at this tier, until it expires or the process
 * exits. Approving the one tool instead meant being asked again by the next command the same agent ran,
 * which is how a careful dialog turned into hours-long open access given just to make it stop.
 *
 * <p>F3 narrows the answer to this one document, which is not anchored and can stand longer.
 *
 * <p><b>Only function keys answer</b>, and only after the window has been left alone for two seconds:
 * F2 approves, Esc denies, F4 allows just this call, F3 switches breadth, the arrow keys move the anchor and
 * the duration. A window that takes the focus while someone is typing must not be answered by what they
 * were typing, so no letter, digit, Space or Enter does anything here, nothing is focusable, there is no
 * default button, and every key that arrives during the settle time starts it again.
 *
 * <p>Colour tracks elevation: green to read, amber to change, red for anything that cannot be undone. The
 * irreversible tier asks for the passphrase before anything can be granted, and keeps its operation budget.
 */
public final class ApprovalWindow {

    /*
     * Every colour is checked against the tinted surface it is drawn on, not against white: AMBER is deep
     * enough that white clears 5:1 on it, and the two inks clear 4.5:1 on all three tints.
     */
    private static final String
            RED = "#b71c1c",        // white on it 6.6:1 · as text on its tint 5.7:1
            AMBER = "#bf4b00",      // white on it 5.0:1 · as text on its tint 4.6:1
            GREEN = "#1b5e20",      // white on it 8.0:1 · as text on its tint 9.6:1
            WARN = "#7d6100",       // 5.4:1 — the colour of "this is not what you think it is"
            MUTED = "#5b564f",      // 6.3:1 — the 11px explanatory text
            FAINT = "#6e6960";      // 4.7:1 — the 10px lines, and no fainter

    private static final String
            NOTE = "-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";",
            ASIDE = "-fx-font-size: 10px; -fx-text-fill: " + FAINT + ";",
            MONO = "-fx-font-family: 'Consolas'; -fx-font-size: 11px;";

    /** How long the window ignores the keyboard, restarted by every key that arrives meanwhile. */
    private static final long SETTLE_MS = 2000;

    /**
     * Deliberately under the client's 300s read timeout in {@code GrantInitializer}. The wallet has to give
     * up first, or the call dies while this window is still open and a later answer grants a rule for a
     * request that no longer exists.
     */
    private static final int WAIT_SECONDS = 240;

    private static final int WIDTH = 680;

    /** The tallest the command line may be: about three lines, then it scrolls. */
    private static final int COMMAND_MAX_H = 74;

    /** The durations offered, in minutes, each shown only when it is within the cap that applies. */
    private static final int[] DURATIONS = {30, 60, 4 * 60, 24 * 60, 7 * 24 * 60};

    private ApprovalWindow() {
    }

    public static ApprovalAnswer ask(ApprovalAsk ask, WalletSettings settings, Predicate<char[]> verify) {
        var out = new AtomicReference<>(ApprovalAnswer.deny());
        var done = new CountDownLatch(1);
        var stage = new AtomicReference<Stage>();

        javafx.application.Platform.runLater(() -> stage.set(new View(ask, settings, verify, answer -> {
            out.set(answer);
            done.countDown();
        }).stage));
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

    /** One open window and everything it is holding: the chosen anchor, breadth and duration. */
    private static final class View {

        final ApprovalAsk ask;
        final WalletSettings settings;
        final Predicate<char[]> verify;
        final Consumer<ApprovalAnswer> answer;
        final Stage stage;

        final boolean danger;
        final boolean locked;
        /** A rule the CLI is asking about names no document, so there is nothing to narrow it to. */
        final boolean blanketAsk;
        final List<AncestryHop> hops;

        int anchor = -1;
        boolean everything;
        int minutes;
        boolean unlockedByPassphrase;
        boolean decided;
        long settleUntil = System.currentTimeMillis() + SETTLE_MS;

        final VBox chainRows = new VBox(1);
        final HBox chips = new HBox(6);
        final Label scopeLine = new Label();
        final Label blocked = new Label();
        final Label settleLine = new Label();
        final Label gateNote = new Label();
        final PasswordField phrase = new PasswordField();
        final List<Button> granting = new ArrayList<>();

        View(ApprovalAsk ask, WalletSettings settings, Predicate<char[]> verify, Consumer<ApprovalAnswer> answer) {
            this.ask = ask;
            this.settings = settings;
            this.verify = verify;
            this.answer = answer;
            this.danger = ask.tier() == Tier.DESTRUCTIVE;
            this.locked = (danger && settings.destructiveNeedsPassphrase) || ask.requiresPassphrase();
            this.blanketAsk = ask.resource() == null || ask.resource().id() == null;
            this.hops = ask.chain();

            var suggested = ask.suggestedHop();
            anchor = suggested >= 0 && suggested < hops.size() && hops.get(suggested).selectable() ? suggested
                    : hops.stream().filter(AncestryHop::selectable).mapToInt(AncestryHop::hop).max().orElse(-1);
            // Everything this process starts is the answer this window exists to offer; one document is the
            // narrower choice, and the only one when no process in the chain can carry an approval.
            everything = anchor >= 0 && !blanketAsk;

            var frame = Look.frame("approval", false);
            stage = frame.stage;
            stage.initModality(Modality.NONE);

            var root = new VBox(8,
                    lbl(ask.headline(), "-fx-font-size: 16px; -fx-font-weight: bold;"),
                    document(),
                    lbl("account: " + text(ask.account()) + "    profile: " + text(ask.profile())
                            + (ask.correlationCode() == null ? "" : "    request code: " + ask.correlationCode()),
                            NOTE),
                    caller(ask));
            root.setPadding(new javafx.geometry.Insets(14, 18, 14, 18));

            if (!hops.isEmpty()) {
                root.getChildren().addAll(heading("Who is asking — anchor the approval at (↑ ↓)"), chainRows,
                        note("It covers what the anchored process starts, and ends when that process exits."
                                + " The agent is usually right. A terminal or the desktop cannot be picked."));
            }
            scopeLine.setWrapText(true);
            var howLong = heading("For how long (← →)");
            howLong.setStyle(howLong.getStyle() + " -fx-padding: 4 6 0 0;");
            root.getChildren().addAll(scopeLine, new HBox(8, howLong, chips));

            if (locked) root.getChildren().addAll(passphrase());

            var approve = button("F2 · Approve", accent(ask.tier()), this::approve);
            var once = button("F4 · Just this call", "#455a64", this::once);
            var deny = button("Esc · Deny", "#37474f", () -> finish(ApprovalAnswer.deny()));
            granting.addAll(List.of(approve, once));

            blocked.setWrapText(true);
            blocked.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + RED + ";");
            settleLine.setStyle("-fx-font-size: 11px; -fx-text-fill: " + WARN + ";");
            var closing = new Label();
            closing.setStyle(ASIDE);
            var buttons = new HBox(8, approve, once, deny, settleLine);
            buttons.setAlignment(Pos.CENTER_LEFT);
            root.getChildren().addAll(buttons, blocked,
                    note("Only function keys answer: F2 approves · F4 allows this one call and remembers nothing"
                            + " · Esc denies. Letters, digits, Space and Enter do nothing."),
                    closing);

            var band = new Label(badge(ask.tier()));
            band.setMaxWidth(Double.MAX_VALUE);
            band.setStyle("-fx-background-color: " + accent(ask.tier()) + "; -fx-text-fill: white;"
                    + " -fx-font-size: 13px; -fx-font-weight: bold; -fx-padding: 8 16 8 16;");
            var page = new VBox(band, root);
            page.setStyle(Ui.INK + " -fx-background-color: " + tint(ask.tier()) + ";");

            redraw();
            var scene = Look.fit(frame, page, WIDTH, tint(ask.tier()));
            frame.onClose(() -> finish(ApprovalAnswer.deny()));
            scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);

            Ui.toFront(stage);
            // Focus on the passphrase box when there is one, where a stray key is a harmless character;
            // otherwise on nothing that can act.
            if (locked) phrase.requestFocus();
            else page.requestFocus();
            settleTicker();
            countdown(closing);
        }

        // ---------------------------------------------------------------- keys

        void onKey(KeyEvent e) {
            var now = System.currentTimeMillis();
            if (now < settleUntil) {
                // Someone still typing stays locked out: every key in the settle time starts it again.
                settleUntil = now + SETTLE_MS;
                e.consume();
                return;
            }
            var code = e.getCode();
            // The passphrase box keeps its own editing keys, Enter included, which checks the passphrase and
            // grants nothing. Function keys, arrows and Esc still come here.
            if (phrase.isFocused() && !phrase.isDisabled() && !code.isFunctionKey() && code != KeyCode.ESCAPE) return;
            e.consume();
            switch (code) {
                case F2 -> approve();
                case F4 -> once();
                case ESCAPE -> finish(ApprovalAnswer.deny());
                case F3 -> toggleBreadth();
                case UP, DOWN -> moveAnchor(code == KeyCode.DOWN);
                case LEFT, RIGHT -> moveDuration(code == KeyCode.RIGHT);
                default -> {
                }
            }
        }

        void toggleBreadth() {
            if (blanketAsk || anchor < 0) return;
            everything = !everything;
            redraw();
        }

        /** Up is towards the caller, down towards the desktop, as the table is drawn. */
        void moveAnchor(boolean down) {
            var picks = hops.stream().filter(AncestryHop::selectable).mapToInt(AncestryHop::hop).toArray();
            if (picks.length == 0) return;
            var at = Arrays.binarySearch(picks, Math.max(anchor, 0));
            if (at < 0) at = 0;
            anchor = picks[down ? Math.min(at + 1, picks.length - 1) : Math.max(at - 1, 0)];
            redraw();
        }

        void moveDuration(boolean longer) {
            var offered = offered();
            if (offered.isEmpty()) return;
            var at = offered.indexOf(minutes);
            if (at < 0) at = offered.size() - 1;
            minutes = offered.get(longer ? Math.min(at + 1, offered.size() - 1) : Math.max(at - 1, 0));
            redraw();
        }

        // ---------------------------------------------------------------- answering

        boolean open() {
            return !locked || unlockedByPassphrase;
        }

        boolean settled() {
            return System.currentTimeMillis() >= settleUntil;
        }

        void once() {
            if (!settled()) return;
            if (open()) finish(ApprovalAnswer.once());
            else explainLocked();
        }

        void approve() {
            if (!settled() || minutes <= 0) return;
            if (!open()) {
                explainLocked();
                return;
            }
            var ops = danger ? settings.destructiveOps : -1;
            var hop = anchor >= 0 ? hops.get(anchor) : null;
            if (hop != null && (everything || danger)) {
                // Irreversible stays bound to the run even for one document, as it always has been.
                finish(ApprovalAnswer.anchored(hop.id(), hop.image() + " pid " + hop.pid(), minutes, ops,
                        everything ? Match.ANY : Match.EXACT));
                return;
            }
            finish(ApprovalAnswer.forMinutes(minutes, ops, Match.EXACT));
        }

        void explainLocked() {
            gateNote.setText("The passphrase is required before this can be approved. Type it above, then Enter.");
            gateNote.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + RED + ";");
        }

        void finish(ApprovalAnswer a) {
            if (decided) return;
            decided = true;
            answer.accept(a);
            stage.close();
        }

        // ---------------------------------------------------------------- drawing

        /** The longest this answer may stand, as things are chosen now. */
        int cap() {
            if (everything && anchor >= 0) return hops.get(anchor).capMinutes();
            var ceiling = ask.tier().ceiling(blanketAsk);
            if (danger && anchor >= 0) return Math.min(ceiling, hops.get(anchor).capMinutes());
            return ceiling;
        }

        List<Integer> offered() {
            var cap = cap();
            var out = new ArrayList<Integer>();
            for (var d : DURATIONS) if (d < cap) out.add(d);
            if (cap > 0) out.add(cap);
            return out;
        }

        void redraw() {
            chainRows.getChildren().setAll(header());
            for (var h : hops) chainRows.getChildren().add(row(h));

            var offered = offered();
            if (!offered.contains(minutes)) minutes = offered.isEmpty() ? 0 : offered.getLast();
            chips.getChildren().clear();
            for (var d : offered) {
                var chip = new Button(Span.describe(d));
                chip.setFocusTraversable(false);
                var on = d == minutes;
                chip.setStyle("-fx-font-size: 12px; -fx-padding: 4 10 4 10; -fx-background-radius: 3;"
                        + (on ? " -fx-background-color: " + accent(ask.tier()) + "; -fx-text-fill: white;"
                                + " -fx-font-weight: bold;"
                        : " -fx-background-color: white; -fx-border-color: #cfc8bb; -fx-border-radius: 3;"));
                chip.setOnAction(e -> {
                    minutes = d;
                    redraw();
                });
                chips.getChildren().add(chip);
            }

            scopeLine.setText(scopeWords());
            scopeLine.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;"
                    + (everything ? " -fx-text-fill: " + accent(ask.tier()) + ";" : ""));

            var why = minutes <= 0 ? "Nothing in the chain can carry this approval. F4 allows just this call."
                    : null;
            blocked.setText(why == null ? "" : why);
            blocked.setManaged(why != null);
            blocked.setVisible(why != null);
            granting.getFirst().setDisable(why != null);
        }

        String scopeWords() {
            var hop = anchor >= 0 ? hops.get(anchor) : null;
            var who = hop == null ? null : hop.image() + " (pid " + hop.pid() + ")";
            if (blanketAsk) return "Covers EVERY document on " + text(ask.account()) + ", present and future.";
            var switches = hop == null ? "" : "  (F3 switches)";
            var budget = danger ? ", up to " + settings.destructiveOps + " operations" : "";
            if (everything) {
                return "Covers: everything " + who + " starts — " + verbs(ask.tier()) + " any document on "
                        + text(ask.account()) + budget + "." + switches;
            }
            var doc = ask.resource().label() == null || ask.resource().label().isBlank()
                    ? ask.resource().id() : ask.resource().label();
            return "Covers: only this document, " + doc
                    + (danger && who != null ? ", while " + who + " runs" + budget : "") + "." + switches;
        }

        Node header() {
            var h = cells(cell("", 22), cell("process", 220), cell("pid", 60), cell("alive", 52),
                    cell("covers", 80), cell("kind", 70), cell("longest", 80));
            h.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: " + FAINT + ";");
            return h;
        }

        Node row(AncestryHop h) {
            var chosen = h.hop() == anchor;
            var mark = !h.selectable() ? "—" : chosen ? "●" : "○";
            var name = "  ".repeat(Math.min(h.hop(), 8)) + (h.hop() > 0 ? "└ " : "") + h.image()
                    ;
            var line = cells(cell(mark, 22), cell(name, 220), cell(String.valueOf(h.pid()), 60),
                    cell(age(h.startMillis()), 52), cell(h.descendants() + " procs", 80), cell(h.hop() == 0 ? "caller" : h.category(), 70),
                    cell(h.selectable() ? Span.describe(h.capMinutes()) : "cannot", 80));
            line.setStyle(MONO + " -fx-padding: 2 4 2 4;"
                    + (chosen ? " -fx-background-color: white; -fx-border-color: " + accent(ask.tier()) + ";"
                    + " -fx-font-weight: bold;" : ""));
            if (!h.selectable()) line.setOpacity(0.6);
            else line.setOnMouseClicked(e -> {
                anchor = h.hop();
                redraw();
            });
            return line;
        }

        Node document() {
            if (blanketAsk) {
                return lbl("EVERY document, present and future",
                        "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + WARN + ";");
            }
            var unresolved = ask.resource().label() == null || ask.resource().label().isBlank();
            return new VBox(2,
                    lbl(unresolved ? "unidentified resource" : ask.resource().label(),
                            "-fx-font-size: 15px; -fx-font-weight: bold;"
                                    + (unresolved ? " -fx-text-fill: " + WARN + ";" : "")),
                    lbl(text(ask.resourceKind()) + "  ·  " + text(ask.resource().id()),
                            MONO + " -fx-text-fill: " + (unresolved ? WARN : MUTED) + ";"));
        }

        List<Node> passphrase() {
            phrase.setPromptText("wallet passphrase, then Enter");
            phrase.setPrefColumnCount(16);
            phrase.setOnAction(e -> {
                var typed = phrase.getText().toCharArray();
                try {
                    if (!verify.test(typed)) {
                        phrase.clear();
                        gateNote.setText("That is not this wallet's passphrase. Cleared — type it again.");
                        gateNote.setStyle("-fx-font-size: 11px; -fx-text-fill: " + RED + ";");
                        return;
                    }
                    phrase.setDisable(true);
                    unlockedByPassphrase = true;
                    gateNote.setText("Confirmed. F2 approves as chosen above.");
                    gateNote.setStyle("-fx-font-size: 11px; -fx-text-fill: " + GREEN + ";");
                    // The Enter that confirmed it, and any repeat, must not reach a choice.
                    settleUntil = System.currentTimeMillis() + SETTLE_MS;
                    stage.getScene().getRoot().requestFocus();
                } finally {
                    Arrays.fill(typed, '\0');
                }
            });
            gateNote.setWrapText(true);
            return List.of(
                    lbl(danger ? "This cannot be undone, so the passphrase is required before it can be approved."
                            : "This covers documents nobody has named, so the passphrase is required before it"
                            + " can be approved.", "-fx-font-size: 12px; -fx-font-weight: bold;"),
                    new HBox(8, phrase), gateNote);
        }

        /** Says, while it lasts, that keys are being ignored, so a person waiting for it knows why. */
        void settleTicker() {
            // The buttons follow the settle time as well: a click is no more an answer than a key while the
            // window has only just appeared under the pointer.
            var clock = new Timeline(new KeyFrame(Duration.millis(200), e -> {
                var left = settleUntil - System.currentTimeMillis();
                settleLine.setText(left > 0 ? "keys ignored for " + (left + 999) / 1000 + "s" : " ");
                granting.get(0).setDisable(left > 0 || minutes <= 0);
                granting.get(1).setDisable(left > 0);
            }));
            clock.setCycleCount(Animation.INDEFINITE);
            clock.play();
            stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> clock.stop());
        }

        void countdown(Label closing) {
            var left = new java.util.concurrent.atomic.AtomicInteger(WAIT_SECONDS);
            var clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
                var n = left.decrementAndGet();
                closing.setText(n <= 0 ? "closing…"
                        : "refused automatically in " + n / 60 + ":" + String.format("%02d", n % 60));
            }));
            clock.setCycleCount(Animation.INDEFINITE);
            clock.play();
            stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> clock.stop());
            closing.setText("refused automatically in " + WAIT_SECONDS / 60 + ":"
                    + String.format("%02d", WAIT_SECONDS % 60));
        }
    }

    // -------------------------------------------------------------------- small parts

    /** Clickable, never focusable, never a default button: the mouse works and no key can reach it. */
    private static Button button(String text, String colour, Runnable act) {
        var b = new Button(text);
        b.setFocusTraversable(false);
        b.setDefaultButton(false);
        b.setCancelButton(false);
        b.setStyle("-fx-background-color: " + colour + "; -fx-text-fill: white; -fx-font-weight: bold;"
                + " -fx-font-size: 13px; -fx-padding: 6 14 6 14; -fx-background-radius: 3;");
        b.setOnAction(e -> act.run());
        return b;
    }

    private static Label heading(String text) {
        var l = new Label(text);
        l.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6 0 0 0;");
        return l;
    }

    private static Label lbl(String text, String style) {
        var l = new Label(text);
        l.setWrapText(true);
        l.setStyle(style);
        return l;
    }

    private static Label note(String text) {
        var l = new Label(text);
        l.setWrapText(true);
        l.setStyle(NOTE);
        return l;
    }

    private static HBox cells(Node... cells) {
        var h = new HBox(6, cells);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static Node cell(String text, double width) {
        var l = new Label(text);
        l.setMinWidth(width);
        l.setPrefWidth(width);
        l.setMaxWidth(width);
        return l;
    }

    private static String age(long startMillis) {
        if (startMillis <= 0) return "?";
        var s = Math.max(0, (System.currentTimeMillis() - startMillis) / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return s / 60 + "m";
        if (s < 86400) return s / 3600 + "h";
        return s / 86400 + "d";
    }

    /**
     * Where the command was run, and what the command was: the directory is the fact the decision is made
     * on, and the command shows what is being planned. The command scrolls past three lines.
     */
    private static Node caller(ApprovalAsk ask) {
        var c = ask.callerOrUnknown();
        var box = new VBox(4);

        var dir = new Label(c.workingDir() == null ? "directory not stated" : "in  " + c.workingDir());
        dir.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
        dir.setStyle("-fx-font-size: 12px; -fx-font-family: 'Consolas';"
                + (c.workingDir() == null ? " -fx-text-fill: " + WARN + ";" : ""));
        box.getChildren().add(dir);

        var line = new Label(c.commandLine() == null ? "command not stated" : c.commandLine());
        line.setWrapText(true);
        line.setStyle(MONO + " -fx-padding: 4 6 4 6; -fx-text-fill: " + (c.commandLine() == null ? WARN : MUTED) + ";");

        // A white inset so the command reads as a quoted thing. The height follows the label's real laid-out
        // height, because a wrapped label reports one line as its preferred height until it is laid out.
        var scroller = new javafx.scene.control.ScrollPane(line);
        scroller.setFitToWidth(true);
        scroller.setFocusTraversable(false);
        scroller.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setMaxHeight(COMMAND_MAX_H);
        line.heightProperty().addListener((o, was, now) ->
                scroller.setPrefHeight(Math.min(now.doubleValue() + 2, COMMAND_MAX_H)));
        scroller.setStyle("-fx-background: #ffffff; -fx-background-color: #ffffff;"
                + " -fx-padding: 0; -fx-background-insets: 0; -fx-border-color: #e2dccf; -fx-border-width: 1;");
        VBox.setVgrow(scroller, Priority.NEVER);
        box.getChildren().add(scroller);

        // An undeclared caller is told on, not squeezed: the declaration cannot be verified anyway.
        if (!c.declared()) {
            var warn = new Label(c.commandLine() == null
                    ? "This caller did not say what it is running; there is less here to judge it by than usual."
                    : "This caller did not state its command; the line above was recovered and may be partial.");
            warn.setWrapText(true);
            warn.setStyle("-fx-font-size: 11px; -fx-text-fill: " + WARN + ";");
            box.getChildren().add(warn);
        }
        box.setMinWidth(Region.USE_PREF_SIZE);
        return box;
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

    /** What a rule at this tier lets through: each tier includes the ones below it. */
    private static String verbs(Tier tier) {
        return switch (tier) {
            case DESTRUCTIVE -> "read, change, delete, move or share";
            case MUTATE -> "read and change";
            case READ -> "read";
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

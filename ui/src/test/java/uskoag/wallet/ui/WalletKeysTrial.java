package uskoag.wallet.ui;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import uskoag.wallet.daemon.WalletCore;
import uskoag.wallet.wire.ApprovalAnswer;
import uskoag.wallet.wire.Health;
import uskoag.wallet.wire.Json;
import uskoag.wallet.wire.Match;
import uskoag.wallet.wire.ResourceRef;
import uskoag.wallet.wire.Tier;
import uskoag.wallet.wire.Tier2;
import uskoag.wallet.wire.TokenInfo;
import uskoag.wallet.wire.WalletPaths;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * No letter, digit, Space, Enter or Delete may act in any tab of the wallet's main window, and each tab's
 * function key must.
 *
 * <p>Acting means what a person would call acting: the wallet's state changed (its standing rules, its
 * accounts, its clients, its settings) or a window opened. Text typed into a box, or a list that moves its
 * selection to the item starting with a letter, is input and not an action.
 *
 * <p>Deterministic: every key is delivered on the FX thread, and the trial then waits for the FX thread to
 * have run everything that key set off before it looks, so nothing depends on how long a sleep happened
 * to be. Stray keys are sent with the focus on each tab's table and a row selected, which is exactly where
 * the old single-letter keys (G grants, E extends, R re-authenticates, Delete removes) used to act.
 *
 * <p><b>Run it against a throwaway keyring, never the real one:</b>
 * <pre>
 * $env:UKAG_WALLET_HOME = "$env:TEMP\wallet-keys-trial"
 * mvn -o -pl ui exec:java -Dexec.classpathScope=test -Dexec.mainClass=uskoag.wallet.ui.WalletKeysTrial
 * </pre>
 */
public final class WalletKeysTrial {

    static final char[] PHRASE = "trial passphrase".toCharArray();
    static final List<KeyCode> STRAY = List.of(KeyCode.A, KeyCode.D, KeyCode.E, KeyCode.G, KeyCode.H, KeyCode.R,
            KeyCode.S, KeyCode.U, KeyCode.Y, KeyCode.DIGIT1, KeyCode.SPACE, KeyCode.ENTER, KeyCode.DELETE,
            KeyCode.BACK_SPACE);
    static int failures;

    public static void main(String[] args) throws Exception {
        if (System.getenv("UKAG_WALLET_HOME") == null) {
            System.out.println("REFUSING: set UKAG_WALLET_HOME to a scratch directory first; this unlocks a keyring"
                    + " and it is not doing that to " + WalletPaths.home());
            return;
        }
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        var core = new WalletCore();
        core.unlock(PHRASE.clone());
        try {
            core.policy.remember(null, null, null, new ResourceRef(null, "1BxiMVs0XRA5nF", null), Tier.READ,
                    new ApprovalAnswer(true, true, 0, 60, Match.EXACT, "trial"), "trial");
            tabs(core);
            confirm();
            authUrl();
        } finally {
            System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
            System.out.flush();
            Runtime.getRuntime().halt(failures == 0 ? 0 : 1);
        }
    }

    /**
     * Each tab: stray keys at its table must change nothing, and its F2 must act. Clients' F2 opens a native
     * file chooser, which a trial must not leave on somebody's desktop, so that tab's F2 is not pressed; its
     * stray keys still are.
     */
    static void tabs(WalletCore core) {
        var tabs = new TabPane();
        var names = List.of("Clients", "Accounts", "Permissions", "Audit", "Settings");
        var panes = List.of(OrgsPane.build(core), AccountsPane.build(core), PolicyPane.build(core),
                AuditPane.build(core), SettingsPane.build(core));
        for (var i = 0; i < panes.size(); i++) tabs.getTabs().add(new Tab(names.get(i), panes.get(i)));
        var stage = fx(() -> {
            var s = new Stage();
            s.setScene(new Scene(tabs, 900, 640));
            s.show();
            return s;
        });
        fx(() -> {
            var tree = (TreeTableView<Row>) tabs.lookup(".tree-table-view");
            var root = new TreeItem<>(Row.note(null, ""));
            var account = new TreeItem<>(Row.account("someone@example.org", "example"));
            account.setExpanded(true);
            account.getChildren().add(new TreeItem<>(Row.token("someone@example.org", fake())));
            root.getChildren().add(account);
            tree.setRoot(root);
            tree.setShowRoot(false);
            tree.getSelectionModel().select(1);
            return null;
        });
        for (var i = 0; i < panes.size(); i++) {
            var index = i;
            var name = names.get(i);
            var table = fx(() -> {
                tabs.getSelectionModel().select(index);
                return selectAndFocusTable(tabs);
            });
            settle();

            String acted = null;
            for (var k : STRAY) {
                var before = state(core);
                var windows = Set.copyOf(stages());
                press(table == null ? fx(() -> target(stage)) : table, k);
                if (!state(core).equals(before) || opened(windows)) {
                    acted = k + " on " + (table == null ? "the focus" : "the table");
                    closeNew(windows);
                    break;
                }
            }
            check("tab " + name + ": no letter, digit, Space, Enter or Delete acts" + why(acted), acted == null);

            if (name.equals("Clients") || name.equals("Audit")) continue;
            if (name.equals("Settings")) fx(() -> { bumpASetting(tabs); return null; });
            var before = state(core);
            var windows = Set.copyOf(stages());
            press(fx(() -> target(stage)), KeyCode.F2);
            var did = !state(core).equals(before) || opened(windows);
            check("tab " + name + ": F2 acts" + (did ? "" : "  <- nothing changed"), did);
            closeNew(windows);
        }
        fx(() -> { stage.close(); return null; });
    }

    /** Focus on the showing tab's table with its first row selected, or null when it has no table. */
    static Node selectAndFocusTable(TabPane tabs) {
        var content = tabs.getSelectionModel().getSelectedItem().getContent();
        for (var n : content.lookupAll(".tree-table-view")) {
            if (n instanceof TreeTableView<?> t) {
                if (t.getSelectionModel().getSelectedIndex() < 0) t.getSelectionModel().select(0);
                t.requestFocus();
                return t;
            }
        }
        for (var n : content.lookupAll(".table-view")) {
            if (n instanceof TableView<?> t) {
                if (!t.getItems().isEmpty()) t.getSelectionModel().select(0);
                t.requestFocus();
                return t;
            }
        }
        return null;
    }

    /** Changes the first text box of the Settings tab, so that saving it is visible in the wallet's settings. */
    static void bumpASetting(TabPane tabs) {
        for (var n : tabs.getSelectionModel().getSelectedItem().getContent().lookupAll(".text-field")) {
            if (n instanceof javafx.scene.control.TextField f && f.isEditable()) {
                f.setText(String.valueOf(Integer.parseInt(f.getText().isBlank() ? "0" : f.getText().trim()) + 1));
                return;
            }
        }
    }

    static void confirm() {
        var answered = new boolean[1];
        var windows = Set.copyOf(stages());
        Platform.runLater(() -> answered[0] = Confirm.ask("Remove token", "Remove it?", "Remove", "Cancel"));
        var stage = waitForNew(windows);
        if (stage == null) { check("confirm opened", false); return; }
        for (var k : STRAY) press(target(stage), k);
        check("confirm: no stray key answers it while it is arming", !answered[0] && fx(stage::isShowing));
        waitArmed(stage);
        for (var k : STRAY) press(target(stage), k);
        check("confirm: no letter, digit, Space or Enter answers it once settled", !answered[0] && fx(stage::isShowing));
        press(target(stage), KeyCode.F2);
        check("confirm: F2 answers yes once settled", answered[0]);
        closeNew(windows);
    }

    static void authUrl() {
        var windows = Set.copyOf(stages());
        AuthUrlWindow.show("someone@example.org", "https://accounts.example.org/consent?x=1");
        var stage = waitForNew(windows);
        if (stage == null) { check("consent link window opened", false); return; }
        for (var k : STRAY) press(target(stage), k);
        check("consent link window: no letter, digit, Space, Enter or Delete closes or acts", fx(stage::isShowing)
                && Set.copyOf(stages()).size() == windows.size() + 1);
        press(target(stage), KeyCode.ESCAPE);
        check("consent link window: Esc closes it", !fx(stage::isShowing));
    }

    /** What acting would change: the rules, the accounts, the clients and the settings. */
    static String state(WalletCore core) {
        return fx(() -> Json.of(core.policy.rules()) + Json.of(core.accounts()) + Json.of(core.orgs())
                + Json.of(core.settings));
    }

    /** Delivers a key on the FX thread, then waits until everything it set off has run. */
    static void press(Node target, KeyCode k) {
        Platform.runLater(() -> fire(target, k));
        settle();
    }

    /**
     * Two round trips through the FX thread: the first runs the key, the second runs anything the key itself
     * queued. A window opened by the key is showing by then, even one opened with showAndWait, since that
     * keeps processing the queue in its own loop.
     */
    static void settle() {
        fx(() -> null);
        fx(() -> null);
    }

    /** Waits on the window's own arming state instead of a clock. */
    static void waitArmed(Stage stage) {
        for (var i = 0; i < 100; i++) {
            if (fx(() -> !leasegate.frame.Arming.blocked(stage.getScene()))) return;
            sleep(50);
        }
    }

    static Stage waitForNew(Set<Window> before) {
        for (var i = 0; i < 100; i++) {
            settle();
            var s = stages().stream().filter(w -> !before.contains(w)).map(w -> (Stage) w).findFirst();
            if (s.isPresent()) return s.get();
            sleep(50);
        }
        return null;
    }

    static boolean opened(Set<Window> before) {
        return stages().stream().anyMatch(w -> !before.contains(w));
    }

    static List<Window> stages() {
        return fx(() -> Window.getWindows().stream().filter(w -> w.isShowing() && w instanceof Stage).toList());
    }

    static void closeNew(Set<Window> before) {
        fx(() -> {
            Window.getWindows().stream().filter(w -> w.isShowing() && w instanceof Stage && !before.contains(w))
                    .toList().forEach(w -> ((Stage) w).close());
            return null;
        });
        settle();
    }

    static Node target(Stage s) {
        return fx(() -> s.getScene().getFocusOwner() != null ? s.getScene().getFocusOwner() : s.getScene().getRoot());
    }

    static TokenInfo fake() {
        var now = System.currentTimeMillis();
        return new TokenInfo("someone@example.org", "docs", "Sheets, Docs & Slides", "trial",
                List.of("https://www.googleapis.com/auth/spreadsheets"), Tier2.SENSITIVE,
                now, now, 1, 1, Health.HEALTHY, "", now, now, 0);
    }

    static String text(KeyCode c) {
        return switch (c) { case SPACE -> " "; case ENTER -> "\r"; case DELETE, BACK_SPACE -> ""; default -> c.getChar().toLowerCase(); };
    }

    static void fire(Node target, KeyCode c) {
        var t = text(c);
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, t, c, false, false, false, false));
        if (!t.isEmpty() && c != KeyCode.ENTER) Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_TYPED, t, "", KeyCode.UNDEFINED, false, false, false, false));
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED, t, c, false, false, false, false));
    }

    static <T> T fx(Supplier<T> s) {
        if (Platform.isFxApplicationThread()) return s.get();
        var f = new CompletableFuture<T>();
        Platform.runLater(() -> { try { f.complete(s.get()); } catch (Throwable t) { f.completeExceptionally(t); } });
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.out.println("FAIL  the FX thread did not answer (a native dialog is open and waiting?): " + e);
            System.out.flush();
            Runtime.getRuntime().halt(3);
            return null;
        }
    }

    static String why(String bad) { return bad == null ? "" : "  <- " + bad; }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); }
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    private WalletKeysTrial() {
    }
}

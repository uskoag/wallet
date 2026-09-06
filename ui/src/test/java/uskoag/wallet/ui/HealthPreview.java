package uskoag.wallet.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.TreeItem;
import javafx.stage.Stage;
import javafx.stage.Window;
import uskoag.wallet.daemon.WalletCore;
import uskoag.wallet.wire.Health;
import uskoag.wallet.wire.Tier2;
import uskoag.wallet.wire.TokenInfo;
import uskoag.wallet.wire.WalletPaths;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Photographs the two new surfaces nobody can check by reading, and reports the numbers behind them.
 *
 * <p><b>One: the Health column actually renders.</b> A {@code TreeTableCell} factory is the classic thing
 * that compiles and then throws at runtime — the row accessor on a tree table is not the one on a flat
 * table, and getting it wrong costs an exception per visible cell in a window whose failures used to be
 * invisible by construction. So every verdict is laid out for real and the rendered words are printed.
 *
 * <p><b>Two: the quiet health passphrase box fits.</b> Its height was estimated rather than measured, and
 * this window does not size itself to its content — a scene an inch too short squeezes every wrapped label
 * to one ellipsised line, which is exactly how six paragraphs of an approval dialog once became "…".
 *
 * <p><b>Run it against a throwaway keyring, never the real one:</b>
 *
 * <pre>
 * $env:UKAG_WALLET_HOME = "$env:TEMP\wallet-preview"
 * mvn -o -pl ui exec:java -Dexec.classpathScope=test -Dexec.mainClass=uskoag.wallet.ui.HealthPreview
 * </pre>
 */
public final class HealthPreview extends Application {

    private static final Path OUT = Path.of("target", "preview");
    private static final char[] PHRASE = "PREVIEWKEYRING".toCharArray();
    private static final long HOUR = 3_600_000L, DAY = 86_400_000L;

    public static void main(String[] args) {
        Application.launch(HealthPreview.class, args);
    }

    @Override
    public void start(Stage ignored) throws Exception {
        Platform.setImplicitExit(false);
        Files.createDirectories(OUT);
        new Thread(HealthPreview::run, "preview").start();
    }

    private static void run() {
        try {
            if (System.getenv("UKAG_WALLET_HOME") == null) {
                System.out.println("REFUSING: set UKAG_WALLET_HOME to a scratch directory first."
                        + " This creates and unlocks a keyring, and it is not doing that to "
                        + WalletPaths.home());
                return;
            }
            System.out.println("keyring: " + WalletPaths.home());
            table();
            prompt();
        } catch (Exception e) {
            e.printStackTrace(System.out);
        } finally {
            System.out.flush();
            Platform.exit();
            Runtime.getRuntime().halt(0);
        }
    }

    /** Every verdict, in one table, laid out and photographed. No keyring and no network needed. */
    private static void table() throws Exception {
        Ui.onFx(() -> {
            var tree = AccountsTable.build();
            var root = new TreeItem<>(Row.note(null, ""));
            var account = new TreeItem<>(Row.account("someone@uskfoundation.or.ke", "uskf"));
            account.setExpanded(true);
            for (var t : fakes()) account.getChildren().add(new TreeItem<>(Row.token(t.account(), t)));
            root.getChildren().add(account);
            tree.setRoot(root);

            var stage = new Stage();
            stage.setTitle("health table preview");
            stage.setScene(luvjfx.Fx.scene(luvjfx.Fx.vbox().padding(8).nodes(luvjfx.Fx.fx(tree))
                    .style(Ui.INK), 900, 320));
            stage.show();
            return null;
        });
        // The first window in a JVM stalls between show() and its first real layout while Modena and the
        // fonts load, so a short sample photographs a half-laid-out window.
        Thread.sleep(3000);
        Ui.onFx(() -> {
            var stage = named("health table preview");
            shoot(stage, "20-health-column");
            var cells = stage.getScene().getRoot().lookupAll(".tree-table-cell").stream()
                    .filter(javafx.scene.control.Labeled.class::isInstance)
                    .map(javafx.scene.control.Labeled.class::cast)
                    .filter(c -> c.getText() != null && !c.getText().isBlank())
                    .filter(c -> c.getStyle().contains("-fx-text-fill"))
                    .toList();
            System.out.println();
            System.out.println("=== the Health column, as rendered ===");
            cells.forEach(c -> System.out.println("  " + pad(c.getText())
                    + c.getStyle().replace("-fx-", "").trim()));
            System.out.println(cells.isEmpty()
                    ? "  FAIL — no styled health cell rendered; the cell factory is not running"
                    : "  " + cells.size() + " health cell(s) rendered with a colour");
            stage.close();
            return null;
        });
    }

    /** One of each, with plausible ages, so the column's wording is judged on the real strings. */
    private static List<TokenInfo> fakes() {
        var now = System.currentTimeMillis();
        return List.of(
                fake("docs", "Sheets, Docs & Slides — edit existing", Health.HEALTHY,
                        now - 40 * DAY, now - 2 * HOUR, 0, "refresh token accepted, and sheets docs"
                                + " slides answered"),
                fake("mail.read", "Mail: read only", Health.STALE, now - 7 * DAY, now - 7 * DAY,
                        now - 6 * HOUR, "Google refused the refresh: invalid_grant. The consent is gone;"
                                + " re-authenticate this account (Accounts tab, R)."),
                fake("drive.full", "Drive: full control", Health.SCOPE_LOST, now - 90 * DAY,
                        now - 3 * DAY, now - 3 * DAY, "drive refused it: HTTP 403"
                                + " ACCESS_TOKEN_SCOPE_INSUFFICIENT"),
                fake("mail.write", "Mail: read, label, draft & send", Health.CLIENT_GONE, now - 200 * DAY,
                        now - 30 * DAY, now - DAY, "Google refused the refresh: invalid_client"),
                fake("drive.read", "Drive: read everything", Health.UNREACHABLE, now - 5 * DAY,
                        now - 26 * HOUR, 0, "could not reach Google: no such host"),
                fake("legacy", "legacy", Health.UNKNOWN, now - 300 * DAY, 0, 0, null));
    }

    private static TokenInfo fake(String group, String label, Health health, long added, long healthy,
                                  long stale, String note) {
        return new TokenInfo("someone@uskfoundation.or.ke", group, label, "preview",
                List.of("https://www.googleapis.com/auth/spreadsheets"), Tier2.SENSITIVE,
                added, healthy, 42, 10, health, note,
                healthy > 0 ? healthy : stale, healthy, stale);
    }

    /** The quiet ask, on a real locked core, measured against the height it was given. */
    private static void prompt() throws Exception {
        var core = new WalletCore();
        core.unlock(PHRASE.clone());
        core.lock();
        try {
            Ui.onFx(() -> {
                UnlockWindow.show(core, null, UnlockAsk.forHealthCheck(30));
                return null;
            });
            Thread.sleep(1500);
            Ui.onFx(() -> {
                var stage = named("unlock");
                if (stage == null) {
                    System.out.println("  FAIL — no unlock window appeared");
                    return null;
                }
                var scene = stage.getScene();
                var body = (javafx.scene.layout.Region) scene.getRoot();
                shoot(stage, "21-health-unlock-prompt");
                var needs = body.prefHeight(scene.getWidth());
                System.out.println();
                System.out.println("=== the quiet health passphrase box ===");
                System.out.println("  scene " + (int) scene.getWidth() + "x" + (int) scene.getHeight()
                        + "   content needs " + (int) Math.ceil(needs) + "px"
                        + "   spare " + (int) (scene.getHeight() - needs) + "px");
                System.out.println("  alwaysOnTop " + stage.isAlwaysOnTop()
                        + "   (false is the point: nothing is blocked behind this one)");
                System.out.println(needs <= scene.getHeight()
                        ? "  ok   it fits, so no wrapped label is squeezed"
                        : "  FAIL it does not fit — labels will ellipsise. Raise the height in UnlockWindow.");
                // The box must be empty. Anything prefilled here would be a passphrase nobody typed, and
                // the photograph of the first run appeared to show two characters in it.
                var box = box(stage);
                System.out.println(box == null ? "  FAIL no passphrase box"
                        : box.getText().isEmpty() ? "  ok   the passphrase box is empty"
                        : "  FAIL the passphrase box already holds " + box.getText().length() + " char(s)");
                System.out.println("  reason line style: " + reason(stage));
                return null;
            });

            // A wrong passphrase, so the one thing that SHOULD be red is seen to be red. The reason line
            // and the failure line are the same label, and until now everything it ever said was red.
            Ui.onFx(() -> {
                var box = box(named("unlock"));
                box.setText("NOTTHEPASSPHRASE");
                box.fireEvent(new javafx.event.ActionEvent());
                return null;
            });
            Thread.sleep(800);
            Ui.onFx(() -> {
                var stage = named("unlock");
                shoot(stage, "22-health-unlock-refused");
                System.out.println("  after a wrong passphrase: " + reason(stage));
                stage.close();
                return null;
            });
        } finally {
            core.lock();
        }
    }

    private static void shoot(Stage stage, String file) {
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null), "png",
                    OUT.resolve(file + ".png").toFile());
            System.out.println("  wrote " + OUT.resolve(file + ".png"));
        } catch (Exception e) {
            System.out.println("  snapshot failed: " + e);
        }
    }

    private static javafx.scene.control.PasswordField box(Stage stage) {
        if (stage == null) return null;
        return (javafx.scene.control.PasswordField) stage.getScene().getRoot().lookup(".password-field");
    }

    /** The label under the box: the reason, or after a failed attempt, the failure. */
    private static String reason(Stage stage) {
        return stage.getScene().getRoot().lookupAll(".label").stream()
                .filter(javafx.scene.control.Labeled.class::isInstance)
                .map(javafx.scene.control.Labeled.class::cast)
                .filter(l -> l.getStyle().contains("text-fill") && l.getText() != null
                        && (l.getText().contains("check") || l.getText().contains("did not unlock")))
                .map(l -> "\"" + (l.getText().length() > 48 ? l.getText().substring(0, 47) + "…" : l.getText())
                        + "\"  " + l.getStyle())
                .findFirst().orElse("(not found)");
    }

    private static Stage named(String title) {
        for (var w : Window.getWindows()) {
            if (w instanceof Stage s && s.getTitle() != null && s.getTitle().contains(title)) return s;
        }
        return null;
    }

    private static String pad(String s) {
        return (s + "                                  ").substring(0, 34);
    }
}

package uskoag.wallet.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.stage.Stage;
import javafx.stage.Window;
import leasegate.core.access.PassphraseGate;
import uskoag.wallet.daemon.Lineage;
import uskoag.wallet.daemon.WalletSettings;
import uskoag.wallet.wire.ApprovalAsk;
import uskoag.wallet.wire.CallerInfo;
import uskoag.wallet.wire.ResourceRef;
import uskoag.wallet.wire.Tier;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Renders each shape of the approval windows to a PNG, so they can be looked at before they ship: a tool's
 * request at each tier (the shared approval window, with this process's own chain, since that window
 * withdraws itself when the requester is not alive), and the confirmations for rules typed at the wallet's
 * command line.
 *
 * <p>{@code mvn -o test-compile exec:java -Dexec.classpathScope=test
 * -Dexec.mainClass=uskoag.wallet.ui.ApprovalPreview} writes {@code ui/target/preview/*.png}. Windows do
 * flash up on screen; that is the point.
 */
public final class ApprovalPreview extends Application {

    private static final Path OUT = Path.of("target", "preview");

    public static void main(String[] args) {
        Application.launch(ApprovalPreview.class, args);
    }

    @Override
    public void start(Stage ignored) throws Exception {
        Platform.setImplicitExit(false);
        Files.createDirectories(OUT);
        new Thread(ApprovalPreview::run, "preview").start();
    }

    private static void run() {
        var settings = new WalletSettings();
        for (var c : cases()) {
            try {
                shoot(c, settings);
            } catch (Exception e) {
                System.out.println(c.name() + ": FAILED " + e);
            }
        }
        Platform.exit();
    }

    private static void shoot(PreviewCase c, WalletSettings settings) throws Exception {
        var closed = new CountDownLatch(1);
        var worker = new Thread(() -> {
            FxGateway.ask(c.ask(), settings, PassphraseGate.external("test"::equals));
            closed.countDown();
        }, "ask");
        worker.setDaemon(true);
        worker.start();

        // Wait for this case's window to appear (the first one in a JVM is slow), then past the settle time.
        for (var i = 0; i < 100 && Ui.onFx(ApprovalPreview::shown) == null; i++) Thread.sleep(100);
        Thread.sleep(2500);
        if (!c.keys().isEmpty()) {
            Ui.onFx(() -> {
                var stage = shown();
                if (stage != null) for (var k : c.keys()) stage.getScene().getRoot().fireEvent(
                        new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", k,
                                false, false, false, false));
                return null;
            });
            Thread.sleep(500);
        }
        Ui.onFx(() -> {
            var stage = shown();
            if (stage == null) return report(c, "no window");
            try {
                ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null), "png",
                        OUT.resolve(c.name() + ".png").toFile());
            } catch (Exception e) {
                System.out.println("  snapshot failed: " + e);
            }
            report(c, "stage " + (int) stage.getWidth() + "x" + (int) stage.getHeight());
            // Denied with Esc rather than closed, so the window answers and the next case starts clean.
            stage.getScene().getRoot().fireEvent(new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ESCAPE,
                    false, false, false, false));
            return null;
        });
        if (!closed.await(5, TimeUnit.SECONDS)) System.out.println("  window did not answer Esc");
        Thread.sleep(300);
    }

    private static Object report(PreviewCase c, String what) {
        System.out.println((c.name() + "                        ").substring(0, 24) + what);
        return null;
    }

    private static Stage shown() {
        for (var w : Window.getWindows()) if (w instanceof Stage s && s.isShowing()) return s;
        return null;
    }

    private static List<PreviewCase> cases() {
        var doc = new ResourceRef("sheets", "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms",
                "Supplier payments tracking (master)");
        return List.of(
                new PreviewCase("1-read", tool(Tier.READ, doc, "read")),
                new PreviewCase("2-mutate", tool(Tier.MUTATE, doc, "update 12 cells")),
                new PreviewCase("3-destructive", tool(Tier.DESTRUCTIVE, doc, "share with an outside address")),
                new PreviewCase("4-unresolved", tool(Tier.MUTATE, new ResourceRef("drive", "0AKz9pQrSTuvUk9PVA", null), "move")),
                new PreviewCase("5-anchor-moved", tool(Tier.MUTATE, doc, "update 12 cells"), null,
                        List.of(javafx.scene.input.KeyCode.UP)),
                new PreviewCase("6-policy-quiet", cli(Tier.READ, null, "stand a rule allowing READ on EVERYTHING",
                        "blanket permission — READ on every account", true)),
                new PreviewCase("7-policy-allow", cli(Tier.MUTATE, doc, "stand a rule allowing MUTATE",
                        "spreadsheet", false)));
    }

    /** A tool's request, with this process's real chain standing in for the tool's. */
    private static ApprovalAsk tool(Tier tier, ResourceRef res, String op) {
        return new ApprovalAsk("req-9f21", "K7QM", "default", "a-sheets-tool", "someone@example.org", "sheets",
                op, res, "spreadsheet", tier, 1,
                new CallerInfo("C:\\work\\reports", "a-sheets-tool get 1BxiMVs0XRA5nF --range Sheet1!A1:Z400", true),
                ProcessHandle.current().pid(), "invoice reconciliation, March", false,
                Lineage.of(ProcessHandle.current().pid()).hops());
    }

    private static ApprovalAsk cli(Tier tier, ResourceRef res, String op, String kind, boolean wide) {
        return new ApprovalAsk("cli", "CLI ", null, "uskoag-walletcli", "someone@example.org", "sheets", op, res,
                kind, tier, 1, new CallerInfo("C:\\work\\reports", "uskoag-walletcli policy quiet --tier read", true),
                ProcessHandle.current().pid(), "cli", wide);
    }
}

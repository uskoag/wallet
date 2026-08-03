package uskoag.wallet.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.stage.Stage;
import javafx.stage.Window;
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
 * Renders every shape of the approval dialog to a PNG and prints its geometry, so the thing can be
 * LOOKED AT before it is shipped.
 *
 * <p>It exists because this window has now been re-sized three times on description alone — too tall with
 * dead space, then too short with a scroll bar — and each revision was verified by asking someone to
 * trigger a real approval and say how it felt. The height depends on the tier, on whether Google returned
 * a document name, on how long that name is and on the display's scaling, so the only honest check is to
 * lay out all of those and measure them.
 *
 * <p>{@code mvn -o -pl ui exec:java -Dexec.classpathScope=test
 * -Dexec.mainClass=uskoag.wallet.ui.ApprovalPreview} — writes {@code ui/target/preview/*.png} and a line
 * per case saying whether it fits without scrolling. Windows do flash up on screen; that is the point.
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
            ApprovalWindow.ask(c.ask(), settings, p -> new String(p).equals("test"));
            closed.countDown();
        }, "ask");
        worker.setDaemon(true);
        worker.start();

        // The FIRST window in a JVM stalls between show() and its first real pulse while Modena and the
        // fonts load, so a short sample photographs a half-laid-out window and reports a size nobody will
        // ever see. Generous, because a preview that lies is worse than a preview that is slow.
        Thread.sleep(3000);
        if (c.wrongPhrase() != null) {
            Ui.onFx(() -> {
                var stage = approvalStage();
                if (stage != null && stage.getScene().lookup(".password-field")
                        instanceof javafx.scene.control.PasswordField pf) {
                    pf.setText(c.wrongPhrase());
                    pf.fireEvent(new javafx.event.ActionEvent());
                }
                return null;
            });
            Thread.sleep(800);
        }
        Ui.onFx(() -> {
            var stage = approvalStage();
            if (stage == null) return report(c, "no window");
            var scene = stage.getScene();
            var sp = (javafx.scene.control.ScrollPane) scene.getRoot();
            var content = (javafx.scene.layout.Region) sp.getContent();
            try {
                var img = scene.snapshot(null);
                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png",
                        OUT.resolve(c.name() + ".png").toFile());
            } catch (Exception e) {
                System.out.println("  snapshot failed: " + e);
            }
            report(c, "stage " + (int) stage.getWidth() + "x" + (int) stage.getHeight()
                    + "  scene " + (int) scene.getWidth() + "x" + (int) scene.getHeight()
                    + "  content " + (int) content.getWidth() + "x" + (int) content.getHeight()
                    + "  wasted " + (int) (scene.getHeight() - content.getHeight()) + "px"
                    + "  bar " + sp.getVbarPolicy());
            stage.close();
            return null;
        });
        closed.await(5, TimeUnit.SECONDS);
    }

    private static Object report(PreviewCase c, String what) {
        System.out.println(pad(c.name()) + what);
        return null;
    }

    private static String pad(String s) {
        return (s + "                        ").substring(0, 24);
    }

    private static Stage approvalStage() {
        for (var w : Window.getWindows())
            if (w instanceof Stage s && s.getTitle() != null && s.getTitle().contains("approval")) return s;
        return null;
    }

    /**
     * The shapes that actually differ in height: the three tiers, a name Google would not resolve, and a
     * blanket grant, which has no document line, no breadth checkbox and no confirmation code and was the
     * case that opened with a panel of empty space under it.
     *
     * <p>Cases 7 and 8 are the caller block added in PRP 02, and they are here because both of its rows
     * are new height. A one-line command must cost one line; a command longer than the window is wide must
     * stop at three and scroll, never grow the window. And an undeclared caller adds a wrapped amber
     * paragraph, which is the tallest that block ever gets.
     */
    private static List<PreviewCase> cases() {
        var doc = new ResourceRef("sheets", "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms",
                "Supplier payments tracking (master)");
        return List.of(
                new PreviewCase("1-read", ask(Tier.READ, doc, "read", false)),
                new PreviewCase("2-mutate", ask(Tier.MUTATE, doc, "update 12 cells", false)),
                new PreviewCase("3-destructive", ask(Tier.DESTRUCTIVE, doc, "share with an outside address", false)),
                new PreviewCase("4-unresolved",
                        ask(Tier.MUTATE, new ResourceRef("drive", "0AKz9pQrSTuvUk9PVA", null), "move", false)),
                new PreviewCase("5-blanket", ask(Tier.READ, null, "read anything", true)),
                new PreviewCase("6-rejected", ask(Tier.DESTRUCTIVE, doc, "delete", false), "notthisone"),
                new PreviewCase("7-long-command", with(ask(Tier.MUTATE, doc, "update 12 cells", false),
                        new CallerInfo(LONG_DIR, LONG_CMD, true))),
                new PreviewCase("8-undeclared", with(ask(Tier.MUTATE, doc, "update 12 cells", false),
                        CallerInfo.UNKNOWN)),
                new PreviewCase("9-scraped", with(ask(Tier.READ, doc, "read", false),
                        new CallerInfo("C:\\user\\code\\uskoag\\wallet",
                                "uskoag.gservices.SpreadsheetCli get 1BxiMVs0XRA5nF --range Sheet1!A1", false))));
    }

    private static final String LONG_DIR =
            "C:\\user\\code\\projects\\quarterly review notes\\batch\\run-14";

    private static final String LONG_CMD =
            "uskoag-gsheetscli update 1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms --email"
            + " someone@example.org --range \"Sheet1!A2:M480\" --values-file"
            + " C:\\user\\code\\reports-tool\\leads\\out\\round-3-classified.tsv --value-input-option"
            + " USER_ENTERED --skip-if \"stage=='closed' || stage=='bounced'\" --json --no-live --retry 3";

    private static ApprovalAsk with(ApprovalAsk a, CallerInfo c) {
        return new ApprovalAsk(a.requestId(), a.correlationCode(), a.profile(), a.appName(), a.account(),
                a.api(), a.operation(), a.resource(), a.resourceKind(), a.tier(), a.itemCount(), c,
                a.pid(), a.session(), a.requiresPassphrase());
    }

    private static ApprovalAsk ask(Tier tier, ResourceRef res, String op, boolean needsPhrase) {
        return new ApprovalAsk("req-9f21", "K7QM", "uskoag", "uskoag-gsheetscli",
                "someone@example.org", "sheets", op, res,
                res == null ? "every resource" : "spreadsheet", tier, 1,
                new CallerInfo("C:\\user\\code\\uskoag\\wallet",
                        "uskoag-gsheetscli get 1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"
                        + " --range \"Sheet1!A1:Z400\"", true),
                48120, "invoice reconciliation, March", needsPhrase);
    }
}

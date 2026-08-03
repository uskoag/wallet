package uskoag.wallet.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.stage.Stage;
import javafx.stage.Window;
import uskoag.wallet.daemon.WalletCore;
import uskoag.wallet.wire.ApprovalAnswer;
import uskoag.wallet.wire.Match;
import uskoag.wallet.wire.ResourceRef;
import uskoag.wallet.wire.Span;
import uskoag.wallet.wire.Tier;
import uskoag.wallet.wire.WalletPaths;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Photographs the tray's open-access window and proves the blanket ceiling actually clamps.
 *
 * <p>Two things nobody can check by reading. The window is new, so its height has never been measured;
 * and the ceiling is the whole safety argument for having the window at all, so "it is enforced in
 * PolicyEngine" needs to be a number on a screen rather than a claim in a comment. This project's
 * recorded lesson is that built-but-never-run is where the bugs live, and the last two sessions found
 * five defects that were invisible from the source.
 *
 * <p><b>Run it against a throwaway keyring, never the real one:</b>
 *
 * <pre>
 * $env:UKAG_WALLET_HOME = "$env:TEMP\wallet-preview"
 * mvn -o -pl ui exec:java -Dexec.classpathScope=test -Dexec.mainClass=uskoag.wallet.ui.OpenAccessPreview
 * </pre>
 *
 * It refuses to run against the default home, because creating or unlocking a keyring there would touch
 * the credential store this whole project exists to protect.
 */
public final class OpenAccessPreview extends Application {

    private static final Path OUT = Path.of("target", "preview");
    private static final char[] PHRASE = "PREVIEWKEYRING".toCharArray();

    public static void main(String[] args) {
        Application.launch(OpenAccessPreview.class, args);
    }

    @Override
    public void start(Stage ignored) throws Exception {
        Platform.setImplicitExit(false);
        Files.createDirectories(OUT);
        new Thread(OpenAccessPreview::run, "preview").start();
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

            var core = new WalletCore();
            core.unlock(PHRASE.clone());
            try {
                ceilings(core);
                shoot(core);
            } finally {
                // ArcadeDB is opened by unlock() and keeps non-daemon threads, so without this the JVM
                // never exits and a ten-minute build timeout is the first anybody hears about it. Measured
                // the hard way on the first run of this file.
                core.lock();
            }
        } catch (Exception e) {
            e.printStackTrace(System.out);
        } finally {
            System.out.flush();
            Platform.exit();
            // exec:java runs in Maven's own JVM and something in the Drive/Arcade stack still holds a
            // non-daemon thread after close(). halt rather than exit: no shutdown hooks, nothing to wait
            // for, and this is a preview harness whose whole output has already been printed.
            Runtime.getRuntime().halt(0);
        }
    }

    /**
     * The claim being tested: ask for a year, on a rule that names no document, and get the blanket
     * ceiling back — 24h for READ, 1h for MUTATE — while a rule naming a document still gets the ordinary
     * tier ceiling of a week and a day. If these two rows ever read the same, the ceiling is not being
     * applied and the tray window is writing an all-day write grant.
     */
    private static void ceilings(WalletCore core) throws Exception {
        var year = 60 * 24 * 365;
        System.out.println();
        for (var tier : new Tier[]{Tier.READ, Tier.MUTATE}) {
            System.out.println(pad(tier + " blanket ") + granted(core, tier, null, year)
                    + "   (ceiling " + Span.describe(tier.blanketMaxMinutes) + ")");
            System.out.println(pad(tier + " one doc ") + granted(core, tier, "1BxiMVs0XRA5nF", year)
                    + "   (ceiling " + Span.describe(tier.maxMinutes) + ")");
        }
        System.out.println();
    }

    private static String granted(WalletCore core, Tier tier, String resource, int askedFor) throws Exception {
        var rule = core.policy.remember(null, null, null, new ResourceRef(null, resource, null), tier,
                new ApprovalAnswer(true, true, 0, askedFor, Match.EXACT, "preview"), "preview");
        var minutes = Math.round((rule.expiresAt - System.currentTimeMillis()) / 60_000.0);
        core.policy.revoke(rule.id);
        return "asked " + Span.describe(askedFor) + " → got " + Span.describe((int) minutes);
    }

    private static void shoot(WalletCore core) throws Exception {
        Ui.onFx(() -> {
            OpenAccessWindow.show(core, null);
            return null;
        });
        // Same reason as the approval preview: the first window in a JVM stalls between show() and its
        // first real pulse while Modena and the fonts load, and a short sample photographs a half-laid-out
        // window and reports a size nobody will ever see.
        Thread.sleep(3000);
        measure("read tier", "10-open-access");

        // Switching tier has to re-offer the spans, or the window would let somebody choose 24 hours and
        // then hand them an hour — a button that does not do what it says, which is the defect this
        // project already recorded once when spans past a ceiling were offered and silently clamped.
        Ui.onFx(() -> {
            radios().get(1).fire();
            return null;
        });
        Thread.sleep(600);
        Ui.onFx(() -> {
            System.out.println(pad("spans offered") + spanBox().getItems() + "  selected "
                    + spanBox().getValue());
            return null;
        });
        measure("write tier", "11-open-access-write");

        // The gate itself. Three refusals then the one acceptance, because a control is only proved by the
        // cases it is supposed to stop.
        attempt(core, "mismatched", "PREVIEWKEYRING", "PREVIEWKEYRINGX");
        attempt(core, "both wrong", "NOTTHISONE", "NOTTHISONE");
        attempt(core, "correct", "PREVIEWKEYRING", "PREVIEWKEYRING");

        Ui.onFx(() -> {
            var stage = named("open access");
            if (stage != null) stage.close();
            return null;
        });
    }

    /**
     * Types into both boxes, submits, and says what the wallet did about it: the rules that exist
     * afterwards are the only answer that matters.
     */
    private static void attempt(WalletCore core, String what, String first, String second) throws Exception {
        Ui.onFx(() -> {
            var boxes = passwords();
            if (boxes.size() < 2) return null;
            boxes.get(0).setText(first);
            boxes.get(1).setText(second);
            boxes.get(1).fireEvent(new javafx.event.ActionEvent());
            return null;
        });
        Thread.sleep(500);
        Ui.onFx(() -> {
            var rules = core.policy.rules();
            var open = named("open access") != null && named("open access").isShowing();
            System.out.println(pad(what) + (open ? "refused, window still open" : "GRANTED, window closed")
                    + "   rules now " + rules.size()
                    + (rules.isEmpty() ? "" : "  → " + rules.getFirst().describe()));

            // The question the whole feature exists to answer, and the only one worth asserting: would a
            // document nobody has ever named now go through without a dialog? A rule that exists, is
            // listed and is dead answers "no" while looking exactly like "yes" — which is what an
            // opsBudget of 0 produced on the first run of this harness.
            if (!rules.isEmpty()) {
                var covered = core.policy.matching(null, "someone@example.com", "some-session",
                        new ResourceRef("sheets", "aDocumentNobodyHasEverNamed", null), Tier.MUTATE);
                System.out.println(pad("  covers a new doc?")
                        + (covered == null ? "NO — the rule stands but matches nothing" : "yes, by "
                        + covered.id));
            }
            return null;
        });
    }

    private static void measure(String what, String file) {
        Ui.onFx(() -> {
            var stage = named("open access");
            if (stage == null) {
                System.out.println(pad(what) + "no window");
                return null;
            }
            var scene = stage.getScene();
            var sp = (javafx.scene.control.ScrollPane) scene.getRoot();
            var body = (javafx.scene.layout.Region) sp.getContent();
            try {
                ImageIO.write(SwingFXUtils.fromFXImage(scene.snapshot(null), null), "png",
                        OUT.resolve(file + ".png").toFile());
            } catch (Exception e) {
                System.out.println("  snapshot failed: " + e);
            }
            System.out.println(pad(what)
                    + "stage " + (int) stage.getWidth() + "x" + (int) stage.getHeight()
                    + "  scene " + (int) scene.getWidth() + "x" + (int) scene.getHeight()
                    + "  content " + (int) body.getWidth() + "x" + (int) body.getHeight()
                    + "  wasted " + (int) (scene.getHeight() - body.getHeight()) + "px"
                    + "  bar " + sp.getVbarPolicy());
            return null;
        });
    }

    private static java.util.List<javafx.scene.control.PasswordField> passwords() {
        var stage = named("open access");
        if (stage == null) return java.util.List.of();
        return stage.getScene().getRoot().lookupAll(".password-field").stream()
                .filter(javafx.scene.control.PasswordField.class::isInstance)
                .map(javafx.scene.control.PasswordField.class::cast).toList();
    }

    private static java.util.List<javafx.scene.control.RadioButton> radios() {
        var stage = named("open access");
        if (stage == null) return java.util.List.of();
        return stage.getScene().getRoot().lookupAll(".radio-button").stream()
                .filter(javafx.scene.control.RadioButton.class::isInstance)
                .map(javafx.scene.control.RadioButton.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    private static javafx.scene.control.ChoiceBox<Integer> spanBox() {
        var stage = named("open access");
        return (javafx.scene.control.ChoiceBox<Integer>) stage.getScene().getRoot().lookup(".choice-box");
    }

    private static Stage named(String title) {
        for (var w : Window.getWindows())
            if (w instanceof Stage s && s.getTitle() != null && s.getTitle().contains(title)) return s;
        return null;
    }

    private static String pad(String s) {
        return (s + "                        ").substring(0, 24);
    }
}

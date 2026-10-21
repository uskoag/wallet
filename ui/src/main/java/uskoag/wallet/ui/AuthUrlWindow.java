package uskoag.wallet.ui;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Stage;

import static luvjfx.Fx.button;
import static luvjfx.Fx.hbox;
import static luvjfx.Fx.label;
import static luvjfx.Fx.scene;
import static luvjfx.Fx.textArea;
import static luvjfx.Fx.vbox;

/**
 * The consent URL, in a box you can copy out of.
 *
 * <p>Exists because the automatic browse lands in whatever Windows calls the default browser, which
 * with several org accounts on one machine is regularly signed in as the wrong one — and once it has
 * opened there, the URL is stranded inside a window it cannot easily be got out of. This makes the
 * wrong browser a paste instead of a dead end.
 *
 * <p>Non-modal on purpose: the local receiver is already listening and the consent completes in
 * whichever browser finishes it, so this window must not block the flow it is helping.
 */
public final class AuthUrlWindow {

    private static Stage open;

    private AuthUrlWindow() {
    }

    public static void show(String account, String url) {
        Platform.runLater(() -> {
            if (open != null && open.isShowing()) open.close();
            var frame = Look.frame("consent link: " + account, false);
            var stage = frame.stage;
            open = stage;

            var box = textArea();
            box.attr(t -> {
                t.setText(url);
                t.setWrapText(true);
                t.setPrefRowCount(6);
                t.setEditable(false);
                t.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 11px;");
            });

            var status = label("");
            var copy = button("F2  Copy link");
            var openIt = button("F3  Open in default browser");
            var close = button("Esc  Close");

            copy.attr(b -> b.setOnAction(e -> {
                var content = new ClipboardContent();
                content.putString(url);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                ((TextArea) box.node).selectAll();
                status.text("Copied. Paste it into a browser already signed in as " + account + ".");
            }));
            openIt.attr(b -> b.setOnAction(e -> {
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                    status.text("Opened in the default browser.");
                } catch (Exception ex) {
                    status.text("Could not open a browser - copy the link instead.");
                }
            }));
            close.attr(b -> b.setOnAction(e -> stage.close()));

            var root = vbox().spacing(10).padding(16).nodes(
                    label("Sign in as " + account).style("-fx-font-size: 15px; -fx-font-weight: bold;"),
                    label("If the browser that opened is signed in as somebody else, copy this and paste it"
                            + " into the right one. The wallet is already listening for the answer, so it"
                            + " does not matter which browser finishes it.").wrapText(true),
                    box,
                    hbox().spacing(8).nodes(copy, openIt, close),
                    status.wrapText(true).style("-fx-text-fill: #1b5e20;"),
                    label("F2 copies   |   F3 opens it   |   Esc closes   |   this window closes itself when consent lands")
                            .style("-fx-font-size: 11px; -fx-text-fill: #777;"));

            var sc = Look.put(frame, root.style(Ui.INK).node(), 620, 340);
            new leasegate.frame.FKeys()
                    .bind(javafx.scene.input.KeyCode.F2, () -> ((javafx.scene.control.Button) copy.node).fire())
                    .bind(javafx.scene.input.KeyCode.F3, () -> ((javafx.scene.control.Button) openIt.node).fire())
                    .bind(javafx.scene.input.KeyCode.ESCAPE, stage::close)
                    .install(sc);
            leasegate.frame.Parts.mouseOnly(sc.getRoot());
            stage.setOnShown(e -> Platform.runLater(() -> {
                stage.toFront();
                stage.requestFocus();
                ((TextArea) box.node).selectAll();
                box.node.requestFocus();
            }));
            Ui.toFront(stage);
        });
    }

    /** Called once the consent has landed, so a stale link is not left inviting a second attempt. */
    public static void dismiss() {
        Platform.runLater(() -> {
            if (open != null && open.isShowing()) open.close();
            open = null;
        });
    }
}

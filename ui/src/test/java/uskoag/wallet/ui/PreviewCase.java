package uskoag.wallet.ui;

import javafx.scene.input.KeyCode;
import uskoag.wallet.wire.ApprovalAsk;

import java.util.List;

/**
 * One approval-dialog shape to lay out and photograph.
 *
 * @param wrongPhrase submit this into the passphrase box before measuring, or null. It exists to grow the
 *                    window after it has opened — the rejection message wraps to two lines — which is the
 *                    case a window sized once at startup gets wrong.
 * @param keys        pressed, after the settle time, before the photograph: how a case shows the window
 *                    with another anchor or breadth chosen
 */
public record PreviewCase(String name, ApprovalAsk ask, String wrongPhrase, List<KeyCode> keys) {

    public PreviewCase(String name, ApprovalAsk ask) {
        this(name, ask, null, List.of());
    }

    public PreviewCase(String name, ApprovalAsk ask, String wrongPhrase) {
        this(name, ask, wrongPhrase, List.of());
    }
}

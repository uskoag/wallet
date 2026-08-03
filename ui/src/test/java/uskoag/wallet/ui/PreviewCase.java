package uskoag.wallet.ui;

import uskoag.wallet.wire.ApprovalAsk;

/**
 * One approval-dialog shape to lay out and photograph.
 *
 * @param wrongPhrase submit this into the passphrase box before measuring, or null. It exists to grow the
 *                    window after it has opened — the rejection message wraps to two lines — which is the
 *                    case a window sized once at startup gets wrong.
 */
public record PreviewCase(String name, ApprovalAsk ask, String wrongPhrase) {

    public PreviewCase(String name, ApprovalAsk ask) {
        this(name, ask, null);
    }
}

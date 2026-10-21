package uskoag.wallet.ui;

import leasegate.frame.GateFrame;

/**
 * A yes/no box that no stray keystroke can answer: nothing is answered until it has been quiet for two
 * seconds, then yes is F2 and no is Esc, never a letter, Space or Enter.
 */
public final class Confirm {

    private Confirm() {
    }

    public static boolean ask(String title, String message, String yes, String no) {
        return GateFrame.ask(Look.BRAND, title, message, yes, no);
    }
}

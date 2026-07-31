package uskoag.wallet.wire;

import java.security.SecureRandom;

/**
 * The four characters the client prints to stderr and the approval dialog shows in large type.
 *
 * <p>Not authentication — correlation, exactly the Android "tap the number you see" pattern. Its job
 * is to stop you approving the wrong thing. With four agent runs in flight and a dialog saying "a tool
 * wants to delete 340 files", the only question worth answering is which one, and without this there
 * is no way to answer it at all.
 *
 * <p>Alphabet excludes the characters that get misread aloud or on screen.
 */
public final class CorrelationCode {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    private CorrelationCode() {
    }

    public static String next() {
        var c = new char[4];
        for (var i = 0; i < c.length; i++) c[i] = ALPHABET[RNG.nextInt(ALPHABET.length)];
        return new String(c);
    }
}

package uskoag.wallet.daemon;

import uskoag.wallet.wire.WalletPaths;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A passphrase is any text that is not blank, stored exactly as typed, and a keyring sealed while
 * passphrases were folded (letters only, upper case) still opens with what its owner types.
 *
 * <p>The old-style keyring is made the way the old code made one: sealed under {@link Keyring#normalise}
 * of what was typed, which is exactly the key the old {@code create} derived. The probe then checks that
 * the owner's real passphrase opens it and passes {@code verify}, that it can be changed to a passphrase
 * with digits, spaces, punctuation and mixed case, and that from then on only that exact text works.
 *
 * <p>Not a JUnit test: it prints, and reading the words is the point. Run it with
 * {@code UKAG_WALLET_HOME} pointed at a scratch directory; it refuses to run against the real keyring.
 */
public final class PassphraseProbe {

    private static int failures;

    /** What the owner of an old-style keyring actually types: mixed case with a digit in it. */
    private static final String OLD_TYPED = "MySecret1";

    /** A new passphrase nothing would have allowed before: spaces, digits, punctuation, accents, mixed case. */
    private static final String NEW = "Grüne Wiese 42, ok?";

    public static void main(String[] args) throws Exception {
        var home = WalletPaths.home();
        if (home.equals(Path.of(System.getProperty("user.home"), "uskoag", "wallet"))) {
            System.err.println("refusing to run against the real keyring at " + home
                    + " — set UKAG_WALLET_HOME to a scratch directory first");
            System.exit(2);
        }
        Files.deleteIfExists(WalletPaths.keyringFile());
        System.out.println("keyring: " + WalletPaths.keyringFile() + System.lineSeparator());

        // An old-style keyring, sealed under the folded form, as the old create did.
        var old = new Keyring();
        old.create(Keyring.normalise(OLD_TYPED.toCharArray()));
        old.lock();

        var k = new Keyring();
        check("old-style keyring opens with what its owner types", opens(k, OLD_TYPED));
        check("verify accepts what its owner types", k.verify(OLD_TYPED.toCharArray()));
        check("verify refuses a wrong passphrase", !k.verify("SomethingElse".toCharArray()));

        refused("a blank new passphrase is refused", () -> k.changePassphrase(OLD_TYPED.toCharArray(), "   ".toCharArray()));
        k.changePassphrase(OLD_TYPED.toCharArray(), NEW.toCharArray());
        k.lock();

        var n = new Keyring();
        check("after the change, the new passphrase opens exactly as typed", opens(n, NEW));
        check("verify accepts the new passphrase as typed", n.verify(NEW.toCharArray()));
        check("verify refuses its folded form", !n.verify(Keyring.normalise(NEW.toCharArray())));
        n.lock();
        check("its folded form does not open it", !opens(new Keyring(), "GRÜNEWIESEOK"));
        check("the old passphrase no longer opens it", !opens(new Keyring(), OLD_TYPED));
        check("nor does a different case", !opens(new Keyring(), NEW.toLowerCase()));

        Files.deleteIfExists(WalletPaths.keyringFile());
        var created = new Keyring();
        refused("a blank passphrase cannot create a keyring", () -> created.create(new char[0]));
        created.create("a1".toCharArray());
        created.lock();
        check("a two-character passphrase creates a keyring and opens it", opens(new Keyring(), "a1"));

        System.out.println(System.lineSeparator() + (failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED"));
        System.exit(failures == 0 ? 0 : 1);
    }

    private static boolean opens(Keyring k, String phrase) {
        try {
            k.unlock(phrase.toCharArray());
            return k.unlocked();
        } catch (Exception e) {
            return false;
        }
    }

    interface Step {
        void run() throws Exception;
    }

    private static void refused(String label, Step step) {
        try {
            step.run();
            check(label, false);
        } catch (Exception e) {
            check(label + " (" + e.getMessage() + ")", true);
        }
    }

    private static void check(String label, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + label);
        if (!ok) failures++;
    }
}

package uskoag.wallet.daemon;

import uskoag.wallet.wire.WalletPaths;

import java.nio.file.Path;

/**
 * Does the passphrase that opens the wallet also get past the windows that re-ask for it?
 *
 * <p>For a while it did not. Keyrings are sealed under {@link Keyring#normalise} — letters only, upper
 * case — so the copy held in memory is the folded form, while {@code verify} compared what was typed
 * against it on the nose. The real passphrase was refused and the only string that passed was one the
 * wallet has never shown anybody, which made the tray's open-access window impassable: two boxes
 * agreeing, the right passphrase in both, and a flat denial every time.
 *
 * <p>Nothing caught it because nothing exercised {@code verify} against a keyring created the way real
 * ones are created. That is all this does.
 *
 * <p>Not a JUnit test: it prints, and reading the words is the point. Run it with
 * {@code UKAG_WALLET_HOME} pointed at a scratch directory — it creates and rewrites a keyring, and it
 * refuses to run against the real one.
 */
public final class PassphraseProbe {

    private static int failures;

    /** Mixed case with a digit in it, because that is what someone actually types. */
    private static final String TYPED = "MySecret1";

    public static void main(String[] args) throws Exception {
        var home = WalletPaths.home();
        if (home.equals(Path.of(System.getProperty("user.home"), "uskoag", "wallet"))) {
            System.err.println("refusing to run against the real keyring at " + home
                    + " — set UKAG_WALLET_HOME to a scratch directory first");
            System.exit(2);
        }
        System.out.println("keyring: " + WalletPaths.keyringFile() + System.lineSeparator());

        var fresh = new Keyring();
        fresh.create(TYPED.toCharArray());
        check("as created, typed identically", fresh.verify(TYPED.toCharArray()), true);
        check("as created, all lower", fresh.verify(TYPED.toLowerCase().toCharArray()), true);
        check("the folded form itself", fresh.verify("MYSECRET".toCharArray()), true);
        check("plainly wrong", fresh.verify("SomethingElse".toCharArray()), false);
        // Digits are dropped before the key is derived, so this opens the wallet too. verify has to
        // agree with unlock about that, or the two disagree about what the passphrase even is.
        check("only the digits differ", fresh.verify("MySecret9".toCharArray()), true);
        check("letters differ, digits do not", fresh.verify("MySecret1x".toCharArray()), false);

        // The state the open-access window actually runs in: a keyring opened from disk, holding
        // whichever form managed to open it rather than whatever was typed.
        var opened = new Keyring();
        opened.unlock(TYPED.toCharArray());
        check("after unlock, typed identically", opened.verify(TYPED.toCharArray()), true);
        check("after unlock, all lower", opened.verify(TYPED.toLowerCase().toCharArray()), true);
        check("after unlock, wrong", opened.verify("nonsense".toCharArray()), false);

        opened.changePassphrase(TYPED.toCharArray(), "SecondOne2".toCharArray());
        check("after a change, the new one", opened.verify("SecondOne2".toCharArray()), true);
        check("after a change, the old one", opened.verify(TYPED.toCharArray()), false);

        opened.lock();
        check("locked, refuses everything", opened.verify("SecondOne2".toCharArray()), false);

        System.out.println(System.lineSeparator()
                + (failures == 0 ? "all as expected" : failures + " NOT as expected"));
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String what, boolean got, boolean want) {
        var ok = got == want;
        if (!ok) failures++;
        System.out.printf("%-32s verify=%-5s expected=%-5s %s%n", what, got, want, ok ? "ok" : "<-- WRONG");
    }
}

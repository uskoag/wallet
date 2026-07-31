package uskoag.wallet.wire;

/**
 * The product's name, in one place.
 *
 * <p>{@code uskoag-wallet} is the name of an executable, not of the thing. This is the USK OAG's
 * <em>GServices</em> wallet: it holds Google credentials for this office's Google tooling, and nothing
 * else. The distinction is not cosmetic — a window titled "wallet" invites the assumption that anything
 * secret belongs in it, and a credential broker that starts accepting passwords, API keys and secrets in
 * general has quietly become the single thing worth stealing on the machine. The scope is in the name so
 * it stays in the name.
 */
public final class Brand {

    /** Full name, for window titles and the tray. */
    public static final String NAME = "USK OAG GServices Wallet";

    /** Where the full name will not fit, but the scope still has to be visible. */
    public static final String SHORT = "GServices Wallet";

    private Brand() {
    }

    /** {@code "USK OAG GServices Wallet — unlock"}, for a window that is doing one particular thing. */
    public static String titled(String what) {
        return NAME + " — " + what;
    }
}

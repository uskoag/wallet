package uskoag.wallet.ui;

/**
 * Why the passphrase is being asked for, and how hard to push for it.
 *
 * <p>The wallet now asks for two quite different reasons and they deserve different manners. A tool has
 * stopped mid-call and is waiting: that window belongs on top, with the caret already in it, because
 * everything is blocked until it is answered. The daily credential check is the other kind — nothing is
 * waiting, it will happily run at lunchtime instead, and a box that seizes the keyboard at boot once a day
 * for a job that is not urgent is a box that gets resented and then dismissed unread.
 *
 * @param because     the line under the heading, saying what wants the passphrase
 * @param blurb       replaces the standing explanation, for an ask that needs its own
 * @param insistent   on top, and pulls the caret across. False for maintenance.
 * @param idleSeconds seconds of no typing before the box hides itself
 */
public record UnlockAsk(String because, String blurb, boolean insistent, int idleSeconds) {

    /** Something is blocked and waiting for an answer, which is every prompt but one. */
    public static UnlockAsk forTool(String because) {
        return new UnlockAsk(because, null, true, 120);
    }

    /**
     * The daily readonly credential check, which needs the keyring open and nothing else.
     *
     * <p>It says what it is for, and it says that ignoring it is a fair answer, because it is: the check
     * runs the next time the wallet is unlocked for any reason at all.
     */
    public static UnlockAsk forHealthCheck(int minutes) {
        return new UnlockAsk(
                "Daily credential check — nothing is waiting on this.",
                "Once a day the wallet asks Google whether each stored credential still works, using"
                        + " read-only calls only. It needs the keyring open to read them. Doing this daily"
                        + " is also what stops Google retiring a credential, or the OAuth client itself,"
                        + " for six months of inactivity."
                        + "\n\nIgnore this if you are busy. It will run the next time you unlock.",
                false, Math.max(60, minutes * 60));
    }
}

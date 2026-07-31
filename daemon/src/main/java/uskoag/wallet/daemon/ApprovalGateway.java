package uskoag.wallet.daemon;

import uskoag.wallet.wire.ApprovalAnswer;
import uskoag.wallet.wire.ApprovalAsk;

/**
 * How the engine reaches a person. The UI module supplies the JavaFX implementation; the engine itself
 * stays headless so it can be built and tested without a display.
 */
public interface ApprovalGateway {

    ApprovalAnswer ask(ApprovalAsk ask);

    /** Raise the unlock window because work arrived while the wallet was locked. */
    void unlockNeeded(String because);

    /**
     * Show the consent URL so it can be copied.
     *
     * <p>Google's helper opens whatever Windows calls the default browser, which is routinely not the
     * one already signed in as the account being consented — and once it has opened there, the URL is
     * inside a window you cannot easily get it out of. Handing over the text makes the wrong browser a
     * nuisance instead of a dead end.
     */
    default void authUrl(String account, String url) {
    }

    /**
     * Bring the wallet's own window forward. Called when a second launch finds this one already
     * running: the expectation when someone starts an app that is already up is that its window
     * appears, not that a second copy argues with the first.
     */
    default void showWindow() {
    }

    /**
     * A tool needs scopes this account has never granted. Answering yes opens a browser, so this is a
     * question and not a notification.
     */
    default boolean consentNeeded(String account, java.util.List<String> missingScopes) {
        return false;
    }

    default boolean interactive() {
        return true;
    }
}

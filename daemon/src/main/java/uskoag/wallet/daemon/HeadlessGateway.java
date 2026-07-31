package uskoag.wallet.daemon;

import uskoag.wallet.wire.ApprovalAnswer;
import uskoag.wallet.wire.ApprovalAsk;

/**
 * What happens with no display: refuse, and say so loudly enough that the failure is diagnosable.
 *
 * <p>Deliberately not "allow because nobody can be asked". An approval nobody can give is a denial, and
 * the alternative would mean the whole policy layer evaporates the moment a scheduled task runs.
 */
public final class HeadlessGateway implements ApprovalGateway {

    @Override
    public ApprovalAnswer ask(ApprovalAsk ask) {
        Log.warn("denied without asking (no display): " + ask.headline() + " on " + ask.resource().display());
        return ApprovalAnswer.deny();
    }

    @Override
    public void unlockNeeded(String because) {
        Log.warn("the wallet is locked and cannot prompt: " + because);
    }

    /** No window to show it in, so stdout — where it can be selected out of the terminal. */
    @Override
    public void authUrl(String account, String url) {
        System.out.println();
        System.out.println("Open this in a browser signed in as " + account + ":");
        System.out.println(url);
        System.out.println();
    }

    @Override
    public boolean interactive() {
        return false;
    }
}

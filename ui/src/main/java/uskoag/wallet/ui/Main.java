package uskoag.wallet.ui;

import javafx.application.Application;

/**
 * Launch trampoline. JavaFX refuses to start when the main class is itself the {@code Application} and
 * the code came from a shaded jar, so entering through a plain class and calling launch is the standard
 * workaround.
 */
public final class Main {

    public static void main(String[] args) {
        Application.launch(WalletApp.class, args);
    }

    private Main() {
    }
}

package uskoag.wallet.daemon;

import java.io.IOException;

/** A second wallet tried to claim a home the first one already owns. */
public final class AlreadyRunning extends IOException {

    public AlreadyRunning(String message) {
        super(message);
    }
}

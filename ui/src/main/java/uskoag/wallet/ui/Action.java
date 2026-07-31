package uskoag.wallet.ui;

/** A button's body, allowed to throw, so each handler is not three lines of try/catch. */
@FunctionalInterface
public interface Action {

    void run() throws Exception;
}

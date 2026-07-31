package uskoag.wallet.daemon;

/**
 * Whether a request proceeds, and the sentence to hand back when it does not.
 *
 * <p>The reason travels with the refusal because a bare 403 is what made the old arrangement hard to
 * work with: it said something was refused and never which thing, whose account, or what to do next.
 * The client prints this text, so it is written for the person reading a terminal.
 */
public record Decision(boolean allowed, String why) {

    static final Decision OK = new Decision(true, null);

    static Decision no(String why) {
        return new Decision(false, why);
    }
}

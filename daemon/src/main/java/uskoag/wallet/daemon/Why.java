package uskoag.wallet.daemon;

/**
 * A failure, in words a person can act on.
 *
 * <p>Written because {@code e.getMessage()} is null on a great many of the exceptions this layer catches —
 * {@code HttpConnectTimeoutException} and most of {@code java.net.http}'s among them — and a health note
 * reading "could not reach slides: null" is worse than no note: it says something went wrong and withholds
 * the only part that would identify it. So the class name is always present, and the cause chain is
 * followed, because the outer exception is routinely the least informative one in it.
 */
public final class Why {

    private Why() {
    }

    public static String of(Throwable t) {
        if (t == null) return "unknown";
        var out = new StringBuilder(describe(t));
        var cause = t.getCause();
        var depth = 0;
        while (cause != null && depth++ < 3) {
            var text = describe(cause);
            if (out.indexOf(text) < 0) out.append(", caused by ").append(text);
            cause = cause.getCause();
        }
        return out.toString();
    }

    private static String describe(Throwable t) {
        var name = t.getClass().getSimpleName();
        var message = t.getMessage();
        return message == null || message.isBlank() ? name : name + ": " + message.strip();
    }
}

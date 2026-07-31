package uskoag.wallet.wire;

/**
 * What the approval dialog has to say, in the words that let a person actually judge it.
 *
 * <p>A prompt that says "grant write access?" is a prompt you will click through. This one names the
 * tool, the operation, the document and the correlation code the client printed to stderr, because
 * with several agent runs in flight the only question worth answering is which one is asking.
 */
public record ApprovalAsk(
        String requestId,
        String correlationCode,
        String profile,
        String appName,
        String account,
        String api,
        String operation,
        ResourceRef resource,
        Tier tier,
        int itemCount,
        String peerCommand,
        long pid,
        String session) {

    public String headline() {
        var what = itemCount > 1 ? operation + " " + itemCount + " items" : operation;
        return appName + " wants to " + what;
    }
}

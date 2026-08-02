package uskoag.wallet.wire;

/**
 * What the approval dialog has to say, in the words that let a person actually judge it.
 *
 * <p>A prompt that says "grant write access?" is a prompt you will click through. This one names the
 * tool, the operation, the document and the correlation code the client printed to stderr, because
 * with several agent runs in flight the only question worth answering is which one is asking.
 *
 * @param resourceKind what the resource turned out to be — {@code FOLDER}, {@code spreadsheet},
 *                     {@code mailbox} — or, when the wallet could not get a name out of Google, the
 *                     reason it could not. Either way it is shown: an unidentified resource is
 *                     something the person approving has to be told, not left to infer from a gap.
 * @param requiresPassphrase demand the passphrase whatever the tier says. The irreversible tier already
 *                     does this by settings; this is for a grant that is wide rather than irreversible,
 *                     where the blast radius comes from breadth. There is no silent default for it,
 *                     which is why it is a component every caller states rather than a flag with a
 *                     convenience constructor: a security requirement that can be omitted will be.
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
        String resourceKind,
        Tier tier,
        int itemCount,
        String peerCommand,
        long pid,
        String session,
        boolean requiresPassphrase) {

    public String headline() {
        var what = itemCount > 1 ? operation + " " + itemCount + " items" : operation;
        return appName + " wants to " + what;
    }
}

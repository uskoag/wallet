package uskoag.wallet.wire;

/**
 * What the wallet makes of the process asking — the answer to "why am I being asked this again?".
 *
 * <p>Answered by the wallet rather than worked out in the client, and that is the point of it. The client
 * cannot see which process the kernel named, cannot see the chain the wallet walked, and cannot see how
 * many standing rules are bound to the run. A diagnostic that recomputes its own version of the answer is
 * worse than none, because it will eventually disagree with the thing it is describing.
 *
 * @param anchor        the session identity a rule would be bound to, or null when the caller could not
 *                      be placed at all
 * @param how           how it was arrived at: declared by the run, or which fallback found it
 * @param verified      true when the calling process came from the kernel rather than from the client's
 *                      own claim. False is not an error; it is the honest state on a platform where the
 *                      lookup is unavailable, or for a connection that had already closed
 * @param boundRules    how many standing rules are pinned to this anchor, or -1 when the wallet is locked
 *                      and cannot say
 */
public record SessionReport(
        String anchor,
        String anchorDescription,
        String how,
        String label,
        String chain,
        boolean verified,
        long callerPid,
        long declaredSourcePid,
        String warning,
        int boundRules) {
}

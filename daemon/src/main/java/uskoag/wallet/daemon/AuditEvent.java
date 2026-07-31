package uskoag.wallet.daemon;

import uskoag.wallet.wire.Tier;

/**
 * One line of the record. Because the proxy sees every request, this captures what was actually
 * attempted rather than only what was approved — including denials, and including the calls that were
 * never prompted because a rule already covered them. That distinction is what makes the log useful for
 * spotting a runaway automation rather than merely replaying your own clicks.
 */
public record AuditEvent(
        long at,
        String tool,
        String account,
        String api,
        String operation,
        Tier tier,
        Verdict verdict,
        int count,
        String session,
        long pid,
        String target,
        String peer) {
}

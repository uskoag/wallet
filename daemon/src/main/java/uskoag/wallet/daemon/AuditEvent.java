package uskoag.wallet.daemon;

import uskoag.wallet.wire.Tier;

/**
 * One line of the record. Because the proxy sees every request, this captures what was actually
 * attempted rather than only what was approved — including denials, and including the calls that were
 * never prompted because a rule already covered them. That distinction is what makes the log useful for
 * spotting a runaway automation rather than merely replaying your own clicks.
 *
 * @param peer the command the caller said it was running, secrets already replaced. Until the caller
 *             started stating this it was always {@code (unknown)}: Windows does not give the JDK a
 *             process's own argv, so the value scraped from {@code ProcessHandle} was empty every time.
 * @param dir  the directory it ran in — the one fact that says which project a line of this log belongs
 *             to. Null for a caller that did not say.
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
        String peer,
        String dir) {
}

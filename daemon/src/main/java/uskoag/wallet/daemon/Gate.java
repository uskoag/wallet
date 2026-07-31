package uskoag.wallet.daemon;

import uskoag.wallet.wire.ApprovalAsk;
import uskoag.wallet.wire.Tier;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * The single choke point: classify what was asked, ask the standing rules, ask a person if the rules
 * cannot answer, and record all three outcomes whichever way it goes.
 *
 * <p>Concurrent identical prompts are collapsed. Without that, a batch of forty deletions against one
 * folder would open forty dialogs and the fortieth would be answered by someone who stopped reading at
 * the second.
 */
public final class Gate {

    private final WalletCore core;
    private final ConcurrentHashMap<String, Semaphore> inFlight = new ConcurrentHashMap<>();

    public Gate(WalletCore core) {
        this.core = core;
    }

    public boolean allows(Grant grant, Classification c) {
        var verdict = core.policy.decide(grant.profile(), grant.account(), grant.session(), c);
        if (verdict == Verdict.PROMPT) verdict = prompt(grant, c);
        record(grant, c, verdict);
        return verdict == Verdict.ALLOW;
    }

    private Verdict prompt(Grant grant, Classification c) {
        if (!core.gateway().interactive()) return Verdict.DENY;

        var key = grant.session() + "|" + c.resource().api() + "|" + c.resource().id() + "|" + c.tier();
        var lock = inFlight.computeIfAbsent(key, k -> new Semaphore(1));
        try {
            lock.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Verdict.DENY;
        }
        try {
            // Someone may have answered the identical question while we queued behind them.
            var already = core.policy.matching(grant.profile(), grant.account(), grant.session(),
                    c.resource(), c.tier());
            if (already != null) return Verdict.ALLOW;

            var answer = core.gateway().ask(new ApprovalAsk(
                    UUID.randomUUID().toString().substring(0, 8), grant.correlationCode(), grant.profile(),
                    grant.appName(), grant.account(), c.resource().api(), c.operation(), c.resource(),
                    c.tier(), c.itemCount(), grant.peerCommand(), grant.pid(), grant.session()));

            if (!answer.allowed()) return Verdict.DENY;
            if (answer.remember()) {
                try {
                    core.policy.remember(grant.profile(), grant.account(), grant.session(),
                            c.resource(), c.tier(), answer, c.operation());
                } catch (Exception e) {
                    Log.error("approved but could not save the rule", e);
                }
            }
            return Verdict.ALLOW;
        } finally {
            lock.release();
            inFlight.remove(key, lock);
        }
    }

    private void record(Grant grant, Classification c, Verdict verdict) {
        if (c.tier() == Tier.READ && c.resource().isBrowse() && verdict == Verdict.ALLOW) return;
        core.audit.record(new AuditEvent(System.currentTimeMillis(), grant.profile(), grant.account(),
                c.resource().api(), c.operation(), c.tier(), verdict, c.itemCount(),
                grant.session(), grant.pid(), c.resource().display(), grant.peerCommand()));
    }
}

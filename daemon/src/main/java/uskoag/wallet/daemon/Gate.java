package uskoag.wallet.daemon;

import uskoag.wallet.wire.ApprovalAsk;
import uskoag.wallet.wire.ResourceRef;
import uskoag.wallet.wire.Tier;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * The single choke point: classify what was asked, ask the standing rules, ask a person if the rules
 * cannot answer, and record all three outcomes whichever way it goes.
 *
 * <p>Before a person is asked anything, the resource is named. An id is not a question anybody can
 * answer, so a dialog carrying only an id gets approved every time and the approval means nothing.
 *
 * <p>Concurrent identical prompts are collapsed. Without that, a batch of forty deletions against one
 * folder would open forty dialogs and the fortieth would be answered by someone who stopped reading at
 * the second.
 */
public final class Gate {

    /**
     * Resolves a resource's human name. The caller supplies it because only the caller holds a token
     * that could ask Google, and the wallet will not invent a name it has not verified.
     */
    public interface Namer {
        ResourceNames.Named name(ResourceRef res);
    }

    private final WalletCore core;
    private final ConcurrentHashMap<String, Semaphore> inFlight = new ConcurrentHashMap<>();

    public Gate(WalletCore core) {
        this.core = core;
    }

    public Decision decide(Grant grant, Classification c, Namer namer) {
        var ruling = core.policy.decide(grant.profile(), grant.account(), grant.session(), c);
        if (ruling.verdict() != Verdict.PROMPT) {
            // Borrow the name the rule recorded when it was approved. Nothing is asked of Google on this
            // path — that is the whole point of the standing rule — so this is the only way the audit
            // line says which document it was.
            var known = ruling.rule() == null || ruling.rule().label == null
                    ? c : c.withResource(c.resource().withLabel(ruling.rule().label));
            record(grant, known, ruling.verdict());
            return ruling.verdict() == Verdict.ALLOW ? Decision.OK
                    : Decision.no("refused by wallet policy: " + known.operation()
                            + " on " + known.resource().display());
        }

        var named = namer.name(c.resource());
        if (named.status() == ResourceNames.Status.UNREACHABLE) {
            // Refused without a dialog, deliberately. Asking someone to approve access that does not
            // exist can only teach the habit of approving, and the call was going to fail at Google
            // anyway — so the useful answer is the reason, not a question.
            record(grant, c, Verdict.DENY);
            return Decision.no("not asking: " + named.detail() + ". There is nothing to approve"
                    + " — check the id, or give " + grant.account() + " access to it and retry.");
        }

        var resolved = named.status() == ResourceNames.Status.RESOLVED;
        // The label is left unset when the name is unknown, so a rule written from this approval never
        // records a guess as if it were the document's name. The reason goes in the dialog instead.
        var enriched = resolved ? c.withResource(c.resource().withLabel(named.name())) : c;
        var kind = resolved ? named.detail() : "NAME UNRESOLVED — " + named.detail();

        var answered = prompt(grant, enriched, kind);
        var verdict2 = answered != null && answered.allowed() ? Verdict.ALLOW : Verdict.DENY;
        record(grant, enriched, verdict2);
        if (verdict2 == Verdict.ALLOW) return Decision.OK;

        // The dialog's own words where it had any — "nobody answered within 240s" is a different
        // problem from "you said no", and a client that reports them identically wastes the reader's
        // time working out which happened.
        var why = answered == null ? "the wallet has no display, so it could not ask"
                : answered.note() == null ? "denied at the wallet dialog" : answered.note();
        return Decision.no(why + ": " + enriched.operation() + " on " + enriched.resource().display());
    }

    private uskoag.wallet.wire.ApprovalAnswer prompt(Grant grant, Classification c, String kind) {
        if (!core.gateway().interactive()) return null;

        var key = grant.session() + "|" + c.resource().api() + "|" + c.resource().id() + "|" + c.tier();
        var lock = inFlight.computeIfAbsent(key, k -> new Semaphore(1));
        try {
            lock.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            // Someone may have answered the identical question while we queued behind them.
            var already = core.policy.matching(grant.profile(), grant.account(), grant.session(),
                    c.resource(), c.tier());
            if (already != null) return uskoag.wallet.wire.ApprovalAnswer.once();

            var answer = core.gateway().ask(new ApprovalAsk(
                    UUID.randomUUID().toString().substring(0, 8), grant.correlationCode(), grant.profile(),
                    grant.appName(), grant.account(), c.resource().api(), c.operation(), c.resource(),
                    kind, c.tier(), c.itemCount(), grant.peerCommand(), grant.pid(), grant.session()));

            if (!answer.allowed()) return answer;
            if (answer.remember()) {
                try {
                    core.policy.remember(grant.profile(), grant.account(), grant.session(),
                            c.resource(), c.tier(), answer, c.operation());
                } catch (Exception e) {
                    Log.error("approved but could not save the rule", e);
                }
            }
            return answer;
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

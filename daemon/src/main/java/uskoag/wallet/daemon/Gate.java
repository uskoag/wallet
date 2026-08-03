package uskoag.wallet.daemon;

import uskoag.wallet.wire.ApprovalAsk;
import uskoag.wallet.wire.ResourceRef;
import uskoag.wallet.wire.Tier;

import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * The single choke point: classify what was asked, ask the standing rules, ask a person if the rules
 * cannot answer, and record all three outcomes whichever way it goes.
 *
 * <p>Before a person is asked anything, the resource is named. An id is not a question anybody can
 * answer, so a dialog carrying only an id gets approved every time and the approval means nothing.
 *
 * <p><b>One question is on screen at a time, process-wide, and the rest queue behind it.</b> This used
 * to collapse only <i>identical</i> questions, which meant a batch touching forty different files
 * opened forty windows at once — they stack, they steal focus from each other, and the fortieth is
 * answered by someone who stopped reading at the second. That is not consent, it is a clicking
 * exercise, and it is how the habit of approving gets trained.
 *
 * <p>The queue is what makes it cheap rather than merely orderly: every waiting request re-checks the
 * standing rules the moment it reaches the front, so one approval given with "everything this run
 * touches" ticked satisfies the whole backlog without another window appearing. The common case
 * collapses to a single dialog for a whole batch.
 */
public final class Gate {

    /**
     * Resolves a resource's human name. The caller supplies it because only the caller holds a token
     * that could ask Google, and the wallet will not invent a name it has not verified.
     */
    public interface Namer {
        ResourceNames.Named name(ResourceRef res);
    }

    /**
     * How long a request will stand in the queue before giving up.
     *
     * <p>Shorter than the client's read timeout on purpose, and it has to account for the queue as well
     * as the dialog: a request that waited out its caller and then wrote a standing rule would be a
     * permission granted for work that had already failed.
     */
    private static final long QUEUE_WAIT_SECONDS = 200;

    private final WalletCore core;

    /**
     * Fair, so a long batch cannot starve the one interactive request a person is actually waiting on.
     */
    private final Semaphore oneAtATime = new Semaphore(1, true);

    public Gate(WalletCore core) {
        this.core = core;
    }

    public Decision decide(Grant grant, Classification c, Namer namer) {
        var ruling = core.policy.decide(grant.profile(), grant.account(), grant.session(), c);
        if (ruling.verdict() != Verdict.PROMPT) {
            /*
             * Name the document that was actually touched, which is NOT always the one the rule remembers.
             *
             * This borrowed rule.label unconditionally, and for an EXACT rule that is right: the rule is
             * about that one document. For any rule broader than one document it is wrong, and silently.
             * A match=ANY rule remembers the single document that happened to be on screen when it was
             * approved, so every later request it covers was logged under that name — four different
             * spreadsheets all recorded as "DocsList", ids correct, names wrong. A blanket rule has no
             * label at all, so everything it covers would have logged as a bare id.
             *
             * The audit is the only record of what this machine did with a credential. A name that is
             * confidently wrong is worse there than no name, because a bare id invites a lookup and a wrong
             * name ends the enquiry.
             *
             * Cache first, since it costs nothing and is usually warm — the first touch of a document
             * resolved it. Then the rule's own label, but only when the rule is about exactly this
             * document. Otherwise the bare id, honestly.
             */
            var rule = ruling.rule();
            var exact = rule != null && rule.match == uskoag.wallet.wire.Match.EXACT
                    && rule.resource != null && rule.resource.equals(c.resource().id());
            var name = core.names.cachedName(uskoag.wallet.wire.GApi.of(c.resource().api()),
                            c.resource().id(), grant.account())
                    .orElse(exact ? rule.label : null);
            var known = name == null ? c : c.withResource(c.resource().withLabel(name));
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

        // Development mode, and it answers before the queue rather than inside it: a debug run is
        // unattended by definition, so making it wait its turn behind a semaphore only slows it down.
        // Always `once` — a debug approval never becomes a standing rule, so nothing it did survives
        // the process. See Debug for why the window is bounded the way it is.
        if (Debug.autoApproves(c.tier())) {
            Log.warn("DEBUG auto-approved " + c.tier() + " " + c.operation() + " on "
                    + c.resource().display());
            return uskoag.wallet.wire.ApprovalAnswer.once();
        }

        boolean mine;
        try {
            mine = oneAtATime.tryAcquire(QUEUE_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (!mine) {
            // Refused rather than queued indefinitely, and said plainly. A bulk caller reads this as a
            // wallet refusal and pauses with a resume instruction, which is the right outcome: the
            // person is evidently not at the machine working through the queue.
            return uskoag.wallet.wire.ApprovalAnswer.denied("nobody answered the queue of pending"
                    + " approvals within " + QUEUE_WAIT_SECONDS + "s");
        }
        try {
            // The re-check that makes queueing cheap. Whoever was in front may have answered a question
            // broad enough to cover this one — that is the whole point of the "everything this run
            // touches" option — in which case this request never becomes a window at all.
            var already = core.policy.matching(grant.profile(), grant.account(), grant.session(),
                    c.resource(), c.tier());
            if (already != null) return uskoag.wallet.wire.ApprovalAnswer.once();

            // Locking while a queue was forming is an answer too, and not one to put a window up for.
            if (!core.keyring.unlocked()) {
                return uskoag.wallet.wire.ApprovalAnswer.denied("the wallet locked while this was queued");
            }

            var answer = core.gateway().ask(new ApprovalAsk(
                    UUID.randomUUID().toString().substring(0, 8), grant.correlationCode(), grant.profile(),
                    grant.appName(), grant.account(), c.resource().api(), c.operation(), c.resource(),
                    kind, c.tier(), c.itemCount(), grant.caller(), grant.pid(), grant.session(),
                    // An ordinary request names one document, so the tier decides: DESTRUCTIVE brings the
                    // passphrase with it via settings, and nothing else needs to.
                    false));

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
            oneAtATime.release();
        }
    }

    private void record(Grant grant, Classification c, Verdict verdict) {
        if (c.tier() == Tier.READ && c.resource().isBrowse() && verdict == Verdict.ALLOW) return;
        var caller = uskoag.wallet.wire.CallerInfo.orUnknown(grant.caller());
        core.audit.record(new AuditEvent(System.currentTimeMillis(), grant.profile(), grant.account(),
                c.resource().api(), Debug.tag(c.operation()), c.tier(), verdict, c.itemCount(),
                grant.session(), grant.pid(), c.resource().display(),
                caller.commandLine(), caller.workingDir()));
    }
}

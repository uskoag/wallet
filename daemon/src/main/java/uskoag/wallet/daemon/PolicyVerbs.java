package uskoag.wallet.daemon;

import uskoag.wallet.wire.ApprovalAnswer;
import uskoag.wallet.wire.ApprovalAsk;
import uskoag.wallet.wire.GApi;
import uskoag.wallet.wire.Json;
import uskoag.wallet.wire.Match;
import uskoag.wallet.wire.PolicyReply;
import uskoag.wallet.wire.PolicyRequest;
import uskoag.wallet.wire.ResourceRef;
import uskoag.wallet.wire.Span;

import java.io.IOException;

/**
 * The policy surface as commands, so an agent can settle "may I?" before starting a batch rather than
 * discovering the answer halfway through one.
 *
 * <p>{@code allow} still goes through the dialog. A process that could grant itself a rule would have
 * made the whole thing decorative, so the CLI can ask for a permission and never award one.
 */
public final class PolicyVerbs {

    private final WalletCore core;

    public PolicyVerbs(WalletCore core) {
        this.core = core;
    }

    public String dispatch(String verb, String body) throws IOException {
        if (!core.keyring.unlocked()) {
            // Ask, rather than only complain. WalletCore.access already raises the window when a tool needs
            // a credential; these verbs did not, so `policy quiet` on a locked wallet printed "unlock the
            // wallet first" with nothing on screen to unlock — and the caller most likely to hit that is a
            // script with no console, which cannot type a passphrase anywhere at all. Same failure shape as
            // the app-key prompt: a message naming a remedy it does not provide.
            core.gateway().unlockNeeded("uskoag-walletcli " + verb.replace('.', ' '));
            return Json.of(PolicyReply.of(false, "locked",
                    "the wallet is locked — its unlock window has been raised; unlock and run this again"));
        }
        return switch (verb) {
            case "policy.list" -> Json.of(PolicyReply.listing(core.policy.rules()));
            case "policy.check" -> check(Json.to(body, PolicyRequest.class));
            case "policy.allow" -> allow(Json.to(body, PolicyRequest.class));
            case "policy.quiet" -> quiet(Json.to(body, PolicyRequest.class));
            case "policy.extend" -> extend(Json.to(body, PolicyRequest.class));
            case "policy.revoke" -> revoke(body);
            case "policy.clear" -> Json.of(PolicyReply.of(true, "cleared", core.policy.clear() + " rule(s) removed"));
            default -> Json.of(PolicyReply.of(false, "unknown", verb));
        };
    }

    private String check(PolicyRequest r) {
        var res = new ResourceRef(r.api(), r.resource(), null);
        var rule = core.policy.matching(r.profile(), r.account(), null, res, r.tier());
        return Json.of(rule == null
                ? PolicyReply.of(false, "not-covered", "nothing standing covers " + r.tier() + " on " + r.resource())
                : PolicyReply.of(true, "covered", rule.describe()));
    }

    private String allow(PolicyRequest r) throws IOException {
        var account = core.resolve(r.account()).orElse(r.account());
        var res = new ResourceRef(r.api(), r.resource(), null);
        var existing = core.policy.matching(r.profile(), account, null, res, r.tier());
        if (existing != null) return Json.of(PolicyReply.of(true, "already", existing.describe()));

        if (!core.gateway().interactive()) {
            return Json.of(PolicyReply.of(false, "no-display", "cannot ask — the wallet has no display"));
        }

        // The consent has to exist before the document is discussed, and the document has to be
        // reachable before anyone is asked about it. Both refusals below are more useful than a dialog.
        ResourceNames.Named named;
        try {
            named = core.nameFor(GApi.of(r.api()), r.resource(), account, r.tier());
        } catch (IOException e) {
            return Json.of(PolicyReply.of(false, "no-consent", e.getMessage()));
        }
        if (named.status() == ResourceNames.Status.UNREACHABLE) {
            return Json.of(PolicyReply.of(false, "unreachable",
                    named.detail() + " — so there is nothing to write a rule about"));
        }
        var resolved = named.status() == ResourceNames.Status.RESOLVED;
        if (resolved) res = res.withLabel(named.name());

        var answer = core.gateway().ask(new ApprovalAsk(
                "cli", "CLI ", r.profile(), "uskoag-walletcli", account, r.api(),
                "stand a rule allowing " + r.tier(), res,
                resolved ? named.detail() : "NAME UNRESOLVED — " + named.detail(),
                r.tier(), 1, cliCaller("policy allow"), ProcessHandle.current().pid(), "cli", false));
        if (!answer.allowed()) return Json.of(PolicyReply.of(false, "denied", "you declined"));

        var wanted = new ApprovalAnswer(true, true,
                r.ops() == 0 ? answer.ops() : r.ops(),
                r.minutes() == 0 ? answer.minutes() : r.minutes(),
                r.match() == null ? Match.EXACT : r.match(), r.reason());
        var rule = core.policy.remember(r.profile(), account, null, res, r.tier(), wanted, r.reason());
        return Json.of(PolicyReply.of(true, "allowed", rule.describe()));
    }

    /**
     * One rule that covers every document, for one tier, until the tier's ceiling. The answer to heavy
     * automation, and the honest form of the request "stop asking me".
     *
     * <p>It exists because the two things that looked like the answer are not. The approval dialog's
     * breadth box is bound to the session that asked, so it cannot span commands however wide it is —
     * six identical READ dialogs for one spreadsheet arrived inside two and a half minutes, each one
     * granting a permission that died with the command. And {@code readRequiresRule = false} in the
     * Settings tab does silence a tier, but it has <em>no expiry at all</em>: its only bound is the next
     * lock, it appears in no listing, and there is nothing to revoke. This has an expiry, shows up in
     * {@code policy list}, and is revocable by id like anything else.
     *
     * <p>Three things bound it. The <b>blanket</b> ceiling, which is shorter than the ordinary tier ceiling
     * and deliberately so — {@code Tier.blanketMaxMinutes}, a day to read anything and four hours to change
     * anything, against a week and a day for a rule about one named document. The passphrase, because
     * breadth is where the blast radius is and a click can be synthesised by
     * anything running as this user. And the refusal below: DESTRUCTIVE can never be blanket, because
     * "delete anything, unattended, for an hour" is not a permission a person can meaningfully hold in
     * mind, and the irreversible tier is the one place where being asked every time is the feature.
     *
     * <p>What is lost, stated plainly: while one of these stands, a poisoned document that reaches a tool
     * can read (or write) anything that account can, and the first anyone knows of it is the audit. That
     * is a real reduction and it is the one being asked for; the expiry is what keeps it from being
     * permanent, and {@code policy list} is what keeps it from being invisible.
     */
    private String quiet(PolicyRequest r) throws IOException {
        var tier = r.tier();
        if (tier == uskoag.wallet.wire.Tier.DESTRUCTIVE) {
            return Json.of(PolicyReply.of(false, "refused",
                    "there is no blanket irreversible permission, and there will not be. Delete, move and"
                    + " share are approved one operation at a time, against a named document, with the"
                    + " passphrase — that is the whole point of the tier. Use 'policy allow' for a"
                    + " specific document instead."));
        }
        if (!core.gateway().interactive()) {
            return Json.of(PolicyReply.of(false, "no-display", "cannot ask — the wallet has no display"));
        }

        // Null rather than "*" throughout: PolicyRule.applies treats null as a wildcard, and null is what
        // describe() renders as "*". Narrowing is still offered, because "every sheet on one account" is a
        // smaller thing to hold in mind than "everything", and someone who knows which they want should
        // be able to say so.
        var account = wild(r.account()) ? null : core.resolve(r.account()).orElse(r.account());
        var api = wild(r.api()) ? null : r.api();

        // No resource, no name lookup: there is no document to ask Google about, which is exactly what
        // makes this the widest thing the wallet can be asked for and why the dialog says so in amber.
        var res = new ResourceRef(api, null, null);
        var scope = (api == null ? "every API" : api) + ", "
                + (account == null ? "every account" : account);

        var answer = core.gateway().ask(new ApprovalAsk(
                "cli", "CLI ", null, "uskoag-walletcli", account, api,
                "stand a rule allowing " + tier + " on EVERYTHING", res,
                "blanket permission — " + scope,
                tier, 1, cliCaller("policy quiet --tier " + tier), ProcessHandle.current().pid(), "cli",
                // Not because it is irreversible; because it is wide.
                true));
        if (!answer.allowed()) return Json.of(PolicyReply.of(false, "denied", "you declined"));

        var wanted = new ApprovalAnswer(true, true, r.ops() == 0 ? answer.ops() : r.ops(),
                r.minutes() == 0 ? answer.minutes() : r.minutes(), Match.EXACT,
                r.reason() == null ? "blanket " + tier : r.reason());
        // session null, so it stands across commands. That is the entire difference between this and the
        // dialog's breadth box, and it is why this one costs a passphrase and that one does not.
        var rule = core.policy.remember(null, account, null, res, tier, wanted, wanted.note());
        return Json.of(PolicyReply.of(true, "quiet", rule.describe()));
    }

    private static boolean wild(String v) {
        return v == null || v.isBlank() || "*".equals(v);
    }

    /**
     * These asks come from inside the wallet answering a socket verb, so there is no client invocation to
     * report and the directory of the wallet process would be a misleading thing to print. Stated as a
     * declaration because it is one: the wallet knows exactly what it is doing here.
     */
    private static uskoag.wallet.wire.CallerInfo cliCaller(String verb) {
        return new uskoag.wallet.wire.CallerInfo(null, "uskoag-walletcli " + verb, true);
    }

    /**
     * Extending is granting, so this asks. Only the wallet's own window may extend without a dialog,
     * because there the click is itself the consent; a request arriving over the socket is a process
     * asking on its own behalf and gets the same treatment as a first touch.
     */
    private String extend(PolicyRequest r) throws IOException {
        var rule = core.policy.rules().stream()
                .filter(x -> r.resource() != null && r.resource().equals(x.id)).findFirst().orElse(null);
        if (rule == null) return Json.of(PolicyReply.of(false, "not-found", String.valueOf(r.resource())));
        if (!core.gateway().interactive()) {
            return Json.of(PolicyReply.of(false, "no-display", "cannot ask — the wallet has no display"));
        }

        var res = new ResourceRef(rule.api, rule.resource, rule.label);
        var answer = core.gateway().ask(new ApprovalAsk(
                "cli", "CLI ", rule.profile, "uskoag-walletcli", rule.account, rule.api,
                "extend an existing " + rule.tier + " permission by " + Span.describe(r.minutes()),
                res, "standing rule " + rule.id + ", " + rule.until(), rule.tier, 1,
                cliCaller("policy extend " + rule.id), ProcessHandle.current().pid(), "cli", false));
        if (!answer.allowed()) return Json.of(PolicyReply.of(false, "denied", "you declined"));

        try {
            return Json.of(PolicyReply.of(true, "extended", core.policy.extend(rule.id, r.minutes())));
        } catch (IOException e) {
            return Json.of(PolicyReply.of(false, "refused", e.getMessage()));
        }
    }

    private String revoke(String body) throws IOException {
        var id = Json.to(body, PolicyRequest.class).resource();
        return Json.of(core.policy.revoke(id)
                ? PolicyReply.of(true, "revoked", id)
                : PolicyReply.of(false, "not-found", id));
    }
}

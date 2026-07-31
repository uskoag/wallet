package uskoag.wallet.daemon;

import uskoag.wallet.wire.ApprovalAnswer;
import uskoag.wallet.wire.ApprovalAsk;
import uskoag.wallet.wire.Json;
import uskoag.wallet.wire.Match;
import uskoag.wallet.wire.PolicyReply;
import uskoag.wallet.wire.PolicyRequest;
import uskoag.wallet.wire.ResourceRef;

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
        if (!core.keyring.unlocked()) return Json.of(PolicyReply.of(false, "locked", "unlock the wallet first"));
        return switch (verb) {
            case "policy.list" -> Json.of(PolicyReply.listing(core.policy.rules()));
            case "policy.check" -> check(Json.to(body, PolicyRequest.class));
            case "policy.allow" -> allow(Json.to(body, PolicyRequest.class));
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
        var res = new ResourceRef(r.api(), r.resource(), null);
        var existing = core.policy.matching(r.profile(), r.account(), null, res, r.tier());
        if (existing != null) return Json.of(PolicyReply.of(true, "already", existing.describe()));

        if (!core.gateway().interactive()) {
            return Json.of(PolicyReply.of(false, "no-display", "cannot ask — the wallet has no display"));
        }
        var answer = core.gateway().ask(new ApprovalAsk(
                "cli", "CLI ", r.profile(), "uskoag-walletcli", r.account(), r.api(),
                "stand a rule allowing " + r.tier(), res, r.tier(), 1, "wallet policy allow",
                ProcessHandle.current().pid(), "cli"));
        if (!answer.allowed()) return Json.of(PolicyReply.of(false, "denied", "you declined"));

        var wanted = new ApprovalAnswer(true, true,
                r.ops() == 0 ? answer.ops() : r.ops(),
                r.minutes() == 0 ? answer.minutes() : r.minutes(),
                r.match() == null ? Match.EXACT : r.match(), r.reason());
        var rule = core.policy.remember(r.profile(), r.account(), null, res, r.tier(), wanted, r.reason());
        return Json.of(PolicyReply.of(true, "allowed", rule.describe()));
    }

    private String revoke(String body) throws IOException {
        var id = Json.to(body, PolicyRequest.class).resource();
        return Json.of(core.policy.revoke(id)
                ? PolicyReply.of(true, "revoked", id)
                : PolicyReply.of(false, "not-found", id));
    }
}

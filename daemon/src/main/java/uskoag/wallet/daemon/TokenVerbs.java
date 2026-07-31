package uskoag.wallet.daemon;

import uskoag.wallet.wire.Asks;
import uskoag.wallet.wire.Json;
import uskoag.wallet.wire.TokenInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Removing, reordering and finding unused tokens — the housekeeping the UI buttons drive. */
public final class TokenVerbs {

    private final WalletCore core;

    public TokenVerbs(WalletCore core) {
        this.core = core;
    }

    public String dispatch(String verb, String body) throws IOException {
        if (!core.keyring.unlocked()) throw new IOException("wallet is locked");
        return switch (verb) {
            case "token.list" -> Json.of(core.accounts());
            case "token.remove" -> remove(Json.to(body, Asks.TokenRef.class));
            case "token.reorder" -> reorder(Json.to(body, Asks.Reorder.class));
            case "token.unused" -> unused(Json.to(body, Asks.Unused.class));
            case "token.removeUnused" -> removeUnused(Json.to(body, Asks.Unused.class));
            default -> Json.of(Asks.Done.no("unknown verb: " + verb));
        };
    }

    /**
     * Removes the token from the wallet only. Google's own grant survives, and saying so matters: a
     * person who removes a token here and believes the app has been revoked has been misled into a
     * false sense of having withdrawn access.
     */
    public String remove(Asks.TokenRef ref) throws IOException {
        var gone = core.keyring.data().credentials().removeIf(
                c -> c.account.equalsIgnoreCase(ref.account()) && c.group.equalsIgnoreCase(ref.group()));
        if (gone) {
            core.keyring.save();
            core.tokens.clear();
        }
        return Json.of(gone
                ? Asks.Done.yes("removed from the wallet. Google's grant is untouched - revoke it at"
                + " myaccount.google.com/permissions if that is what you meant")
                : Asks.Done.no("no such token"));
    }

    /** Explicit preference among tokens that all satisfy a request. Narrower ones seed lower. */
    public String reorder(Asks.Reorder req) throws IOException {
        var tokens = core.keyring.tokensFor(req.account());
        var order = req.groups();
        for (var t : tokens) {
            var at = order.indexOf(t.group);
            t.order = at < 0 ? order.size() + t.scopes().size() : at;
        }
        core.keyring.save();
        return Json.of(Asks.Done.yes("order set for " + req.account()));
    }

    public String unused(Asks.Unused req) {
        return Json.of(Map.of("tokens", stale(req.days())));
    }

    public String removeUnused(Asks.Unused req) throws IOException {
        var doomed = stale(req.days());
        for (var t : doomed) {
            core.keyring.data().credentials().removeIf(
                    c -> c.account.equalsIgnoreCase(t.account()) && c.group.equalsIgnoreCase(t.group()));
        }
        if (!doomed.isEmpty()) {
            core.keyring.save();
            core.tokens.clear();
        }
        return Json.of(Map.of("removed", doomed.stream().map(t -> t.account() + " / " + t.group()).toList(),
                "note", "removed from the wallet only; Google's grants are untouched"));
    }

    /** Never used at all, or not used within the window. Both are worth offering to drop. */
    private List<TokenInfo> stale(int days) {
        var cutoff = days <= 0 ? 0 : System.currentTimeMillis() - days * 86_400_000L;
        var out = new ArrayList<TokenInfo>();
        for (var account : core.keyring.accountNames()) {
            for (var t : core.tokensOf(account)) {
                if (t.useCount() == 0 || (days > 0 && t.lastUsed() < cutoff)) out.add(t);
            }
        }
        return out;
    }
}

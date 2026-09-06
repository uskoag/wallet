package uskoag.wallet.daemon;

import uskoag.wallet.wire.Asks;
import uskoag.wallet.wire.Json;

import java.io.IOException;

/**
 * The health verbs, so the daily check is scriptable and its findings are readable from a terminal.
 *
 * <p>Here for the same reason every other window in this application has a verb: a control only the UI can
 * exercise cannot be put in a batch, and a state only the UI can display cannot be asked about from the
 * shell where the failure was actually noticed.
 */
public final class HealthVerbs {

    private final WalletCore core;
    private final AccountVerbs accounts;

    public HealthVerbs(WalletCore core) {
        this.core = core;
        this.accounts = new AccountVerbs(core);
    }

    public String dispatch(String verb, String body) throws IOException {
        return switch (verb) {
            // Readable while locked would be a lie — the verdicts are inside the keyring — so say which
            // fact it is rather than answering with an empty list, exactly as `accounts` does.
            case "health.list" -> core.keyring.unlocked() ? Json.of(core.health.stored())
                    : Json.of(Asks.Done.no("locked — the health of each credential is stored in the"
                    + " keyring. Unlock the wallet and run this again."));
            case "health.check" -> check(Json.to(body, Asks.CheckHealth.class));
            case "health.reauth" -> accounts.reauth(Json.to(body, Asks.Reauth.class));
            case "health.due" -> Json.of(java.util.Map.of("due", core.health.due(),
                    "unlocked", core.keyring.unlocked()));
            default -> Json.of(Asks.Done.no("unknown verb: " + verb));
        };
    }

    private String check(Asks.CheckHealth req) throws IOException {
        // Answered rather than thrown. Letting the IOException out gave the caller an HTTP 500 carrying
        // "IOException: wallet is locked" — a stack-trace-shaped answer to the most ordinary condition
        // there is, and one whose remedy is a single word. Every other verb here says it in a sentence.
        if (!core.keyring.unlocked()) {
            return Json.of(Asks.Done.no("locked — the check reads the refresh tokens out of the keyring."
                    + " Unlock the wallet and run this again."));
        }
        var ask = req == null ? Asks.CheckHealth.all() : req;
        if (!ask.force() && !core.health.due()) {
            return Json.of(Asks.Done.yes("already checked within the last "
                    + HealthSweep.DUE_MS / 3_600_000L + " hours; pass --force to check anyway"));
        }
        return Json.of(core.health.run(ask.account(), ask.group()));
    }
}

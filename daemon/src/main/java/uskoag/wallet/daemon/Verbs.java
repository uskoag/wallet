package uskoag.wallet.daemon;

import uskoag.wallet.wire.AccessRequest;
import uskoag.wallet.wire.Asks;
import uskoag.wallet.wire.Json;
import uskoag.wallet.wire.Profiles;

import java.util.List;
import java.util.Map;

/** The verb table. Everything the UI can do is here, which is why the CLI can do all of it too. */
public final class Verbs {

    private final WalletCore core;
    private final AccountVerbs accounts;
    private final PolicyVerbs policy;
    private final TokenVerbs tokens;
    private final HealthVerbs health;

    public Verbs(WalletCore core) {
        this.core = core;
        this.accounts = new AccountVerbs(core);
        this.policy = new PolicyVerbs(core);
        this.tokens = new TokenVerbs(core);
        this.health = new HealthVerbs(core);
    }

    public String dispatch(String verb, String body) throws Exception {
        return dispatch(verb, body, 0);
    }

    /**
     * @param peerPid the process the kernel says opened this connection, or 0 when it could not be
     *                established. Only {@code access} uses it, and only to decide which process a run of
     *                work belongs to — never to permit or refuse anything.
     */
    public String dispatch(String verb, String body, long peerPid) throws Exception {
        return switch (verb) {
            case "ping" -> Json.of(Map.of("pong", true));
            case "show" -> {
                core.gateway().showWindow();
                yield Json.of(Asks.Done.yes("window raised"));
            }
            case "status" -> Json.of(core.status());
            case "unlock" -> unlock(body);
            case "lock" -> {
                core.lock();
                yield Json.of(Asks.Done.yes("locked"));
            }
            case "passwd" -> passwd(body);
            case "access" -> Json.of(core.access(Json.to(body, AccessRequest.class), peerPid));
            // Refused rather than answered with an empty list. core.accounts() and core.orgs() both return
            // nothing while the keyring is locked, and `[]` from a verb whose job is to inventory what this
            // machine holds is read as "it holds nothing" — which, of a credential store, reads as loss.
            // Say which of the two facts it is; the caller can then unlock and ask again.
            case "accounts" -> core.keyring.unlocked() ? Json.of(core.accounts())
                    : Json.of(Asks.Done.no("locked — cannot list accounts. Nothing is missing; unlock"
                    + " the wallet and run this again."));
            case "orgs" -> core.keyring.unlocked() ? Json.of(core.orgs())
                    : Json.of(Asks.Done.no("locked — cannot list OAuth clients. Unlock and run again."));
            case "profiles" -> Json.of(Profiles.known());
            case "org.add" -> accounts.addOrg(Json.to(body, Asks.AddOrg.class));
            case "org.domains" -> accounts.setDomains(Json.to(body, Asks.OrgDomains.class));
            case "org.remove" -> accounts.removeOrg(Json.to(body, Asks.OrgRef.class));
            case "org.rename" -> accounts.renameOrg(Json.to(body, Asks.OrgRename.class));
            case "login" -> accounts.login(Json.to(body, Asks.Login.class));
            case "import" -> accounts.importOld(Json.to(body, Asks.Import.class));
            case "export" -> accounts.export(Json.to(body, Asks.Export.class));
            case "forget" -> accounts.forget(Json.to(body, Asks.Forget.class));
            // Prefix routing for these two namespaces is handled in the default branch below, so adding a
            // verb means editing the class that owns it and nothing else.
            // token.* likewise — see the default branch.
            case "groups" -> Json.of(uskoag.wallet.wire.Groups.all());
            // Answered by the wallet rather than worked out in the client, which is the whole point: the
            // client cannot see what the kernel told us, and a diagnostic that reports a different answer
            // from the one being used is worse than none.
            case "session" -> Json.of(core.sessionReport(Json.to(body, AccessRequest.class), peerPid));
            case "audit" -> audit(body);
            case "requests" -> requests(body, false);
            case "requests.replay" -> requests(body, true);
            case "backups" -> Json.of(CredentialsBackup.all().stream().map(CredentialsBackup::orgOf).toList());
            case "purgebackups" -> Json.of(Asks.Done.yes(CredentialsBackup.purge()
                    + " backed-up credentials.json file(s) deleted"));
            case "reset" -> Json.of(Asks.Done.yes(Recovery.reset(core)
                    + "; " + Recovery.pending() + " credentials.json kept."
                    + " Unlock the wallet window to set a new passphrase."));
            case "shutdown" -> {
                new Thread(() -> {
                    sleepQuietly();
                    System.exit(0);
                }).start();
                yield Json.of(Asks.Done.yes("shutting down"));
            }
            /*
             * Namespaces are delegated whole, by prefix, rather than enumerated case by case.
             *
             * The enumerated list was a second place that had to be edited to add a verb, and it was duly
             * forgotten: `policy quiet` was implemented in PolicyVerbs, wired into the CLI, documented in
             * two help texts and shipped — and answered "unknown verb: policy.quiet", because this switch
             * had never heard of it. Nothing was lost by the omission except an evening.
             *
             * All three delegates already end in their own unknown-verb branch, so an unrecognised policy.* or
             * token.* or health.* verb is still refused; it is refused by the class that owns the namespace, which is
             * also the class that knows what the valid ones are.
             */
            default -> {
                if (verb != null && verb.startsWith("policy.")) yield policy.dispatch(verb, body, peerPid);
                if (verb != null && verb.startsWith("token.")) yield tokens.dispatch(verb, body);
                if (verb != null && verb.startsWith("health.")) yield health.dispatch(verb, body);
                yield Json.of(Asks.Done.no("unknown verb: " + verb));
            }
        };
    }

    private String unlock(String body) {
        var req = Json.to(body, Asks.Unlock.class);
        if (req == null || req.passphrase() == null || req.passphrase().isEmpty()) {
            return Json.of(Asks.Done.no("no passphrase supplied"));
        }
        var chars = req.passphrase().toCharArray();
        try {
            core.unlock(chars);
            return Json.of(Asks.Done.yes(core.keyring.data().orgs().size() + " org(s), "
                    + core.keyring.data().credentials().size() + " account(s) available"));
        } catch (Exception e) {
            return Json.of(Asks.Done.no(String.valueOf(e.getMessage())));
        } finally {
            java.util.Arrays.fill(chars, '\0');
        }
    }

    private String passwd(String body) {
        var req = Json.to(body, Asks.Passwd.class);
        var current = req.current().toCharArray();
        var fresh = req.fresh().toCharArray();
        try {
            core.keyring.changePassphrase(current, fresh);
            return Json.of(Asks.Done.yes("passphrase changed — the audit history is unaffected"));
        } catch (Exception e) {
            return Json.of(Asks.Done.no(String.valueOf(e.getMessage())));
        } finally {
            java.util.Arrays.fill(current, '\0');
            java.util.Arrays.fill(fresh, '\0');
        }
    }

    private String audit(String body) {
        if (!core.keyring.unlocked()) return Json.of(List.of());
        var req = Json.to(body, Asks.Recent.class);
        return Json.of(core.audit.recent(req == null || req.limit() <= 0 ? 100 : req.limit()));
    }

    /**
     * The recorded request shapes, or the result of re-classifying them.
     *
     * <p>Locked returns nothing rather than an error, exactly as {@code audit} does: the skeletons are
     * sealed with the keyring's column key, so there is nothing readable to return and nothing has gone
     * wrong.
     */
    private String requests(String body, boolean replay) {
        if (!core.keyring.unlocked()) return Json.of(List.of());
        var req = Json.to(body, Asks.Recent.class);
        var limit = req == null || req.limit() <= 0 ? 100 : req.limit();
        return Json.of(replay ? core.requests.replay(limit) : core.requests.recent(limit));
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

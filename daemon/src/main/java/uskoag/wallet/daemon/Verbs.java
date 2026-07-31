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

    public Verbs(WalletCore core) {
        this.core = core;
        this.accounts = new AccountVerbs(core);
        this.policy = new PolicyVerbs(core);
        this.tokens = new TokenVerbs(core);
    }

    public String dispatch(String verb, String body) throws Exception {
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
            case "access" -> Json.of(core.access(Json.to(body, AccessRequest.class)));
            case "accounts" -> Json.of(core.accounts());
            case "orgs" -> Json.of(core.orgs());
            case "profiles" -> Json.of(Profiles.known());
            case "org.add" -> accounts.addOrg(Json.to(body, Asks.AddOrg.class));
            case "org.domains" -> accounts.setDomains(Json.to(body, Asks.OrgDomains.class));
            case "org.remove" -> accounts.removeOrg(Json.to(body, Asks.OrgRef.class));
            case "org.rename" -> accounts.renameOrg(Json.to(body, Asks.OrgRename.class));
            case "login" -> accounts.login(Json.to(body, Asks.Login.class));
            case "import" -> accounts.importOld(Json.to(body, Asks.Import.class));
            case "export" -> accounts.export(Json.to(body, Asks.Export.class));
            case "forget" -> accounts.forget(Json.to(body, Asks.Forget.class));
            case "policy.list", "policy.check", "policy.allow", "policy.revoke", "policy.clear" ->
                    policy.dispatch(verb, body);
            case "token.list", "token.remove", "token.reorder", "token.unused", "token.removeUnused" ->
                    tokens.dispatch(verb, body);
            case "groups" -> Json.of(uskoag.wallet.wire.Groups.all());
            case "audit" -> audit(body);
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
            default -> Json.of(Asks.Done.no("unknown verb: " + verb));
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

    private static void sleepQuietly() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

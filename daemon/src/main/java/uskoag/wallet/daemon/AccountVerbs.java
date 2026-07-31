package uskoag.wallet.daemon;

import uskoag.wallet.wire.Asks;
import uskoag.wallet.wire.Groups;
import uskoag.wallet.wire.Json;
import uskoag.wallet.wire.Profiles;
import uskoag.wallet.wire.ScopeGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Orgs, accounts, consent, migration, export — the inventory, made explicit. */
public final class AccountVerbs {

    /** What an imported pre-wallet token becomes. Not a real group: its scopes are whatever it had. */
    static final String LEGACY = "legacy";

    private final WalletCore core;

    public AccountVerbs(WalletCore core) {
        this.core = core;
    }

    /**
     * Uploading a credentials.json is a first-class action, from the UI or the CLI, either way — and it
     * lands on the org rather than on a tool, because that is what an OAuth client belongs to.
     */
    public String addOrg(Asks.AddOrg req) throws IOException {
        require();
        var org = core.keyring.org(req.id()).orElseGet(() -> {
            var fresh = new OrgRecord(req.id());
            core.keyring.data().orgs().add(fresh);
            return fresh;
        });
        var secrets = ClientJson.parse(req.credentialsJson());
        org.label = req.label() == null || req.label().isBlank() ? req.id() : req.label();
        org.credentialsJson = req.credentialsJson();
        org.clientId = secrets.get("client_id");
        org.clientSecret = secrets.get("client_secret");
        if (req.domains() != null) req.domains().forEach(d -> {
            if (!d.isBlank() && !org.domains().contains(d.toLowerCase())) org.domains().add(d.toLowerCase());
        });
        core.keyring.save();
        if (core.settings.backupCredentialsJson) CredentialsBackup.save(org.id, req.credentialsJson());
        return Json.of(Asks.Done.yes("org '" + org.id + "' stored"
                + (org.domains().isEmpty() ? "" : " for " + String.join(", ", org.domains()))
                + ". Now: uskoag-walletcli login <email> --groups " + Groups.DOCS.id()));
    }

    /**
     * One consent per group, in sequence. That is the cost of separate tokens and it is the point of
     * them: each expires on its own, so an unused mail grant can no longer take Sheets down with it.
     */
    public String login(Asks.Login req) throws IOException {
        require();
        var org = core.keyring.orgFor(req.org() != null ? req.org()
                        : core.keyring.anyFor(req.account()).map(c -> c.orgId).orElse(null),
                        req.account())
                .orElseThrow(() -> new IOException("cannot tell which org '" + req.account()
                        + "' belongs to. Pass --org, or add one: uskoag-walletcli org add <id>"
                        + " --file credentials.json --domains " + domainOf(req.account())));

        if (org.owner != null && !org.owner.equalsIgnoreCase(req.account())) {
            Log.warn("client '" + org.id + "' was created under " + org.owner + " but you are granting "
                    + req.account() + ". If the Cloud project is still in Testing, add " + req.account()
                    + " to its test users first, or Google will refuse without explaining why.");
        }
        var wanted = resolveGroups(req);
        if (wanted.isEmpty()) throw new IOException("nothing to grant: pass --groups, or --scopes for a"
                + " hand-written set. Known groups: " + Groups.all().stream().map(ScopeGroup::id).toList());

        // Each group is committed on its own. Granting three and having the second refused must not
        // discard the first: a consent already given is a real thing that happened, and throwing it away
        // means going back to Google for a permission the account already holds. The earlier code saved
        // only after the whole loop, so a failure left the successful token in memory, live until the
        // next lock and then silently gone - which is worse than losing it outright.
        var done = new ArrayList<String>();
        var failed = new LinkedHashMap<String, String>();
        for (var group : wanted) {
            try {
                var fresh = OAuthRunner.consent(req.account(), org, group, req.port() <= 0 ? 8888 : req.port(),
                        core.gateway(), core.settings.openBrowserAutomatically);
                core.keyring.find(req.account(), group.id()).ifPresent(core.keyring.data().credentials()::remove);
                // Narrower tokens seed lower so least privilege is the default without anyone ordering them.
                fresh.order = group.rank();
                core.keyring.data().credentials().add(fresh);
                core.keyring.claimDomain(org, req.account());
                if (org.owner == null || org.owner.isBlank()) org.owner = req.account();
                core.keyring.save();
                done.add(group.id());
            } catch (Exception e) {
                failed.put(group.id(), String.valueOf(e.getMessage()));
                Log.warn("consent failed for " + req.account() + " / " + group.id() + " - " + e.getMessage());
            }
        }
        core.tokens.clear();

        if (done.isEmpty()) {
            throw new IOException("nothing was granted. " + describe(failed));
        }
        return Json.of(Map.of(
                "account", req.account(),
                "org", org.id,
                "granted", done,
                "failed", failed,
                "message", done.size() + " granted" + (failed.isEmpty() ? " and kept."
                        : ", " + failed.size() + " failed and can be retried on their own. " + describe(failed))));
    }

    private static String describe(Map<String, String> failed) {
        return failed.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .reduce((a, b) -> a + "; " + b).orElse("");
    }

    /** Named groups, else a hand-written scope set, else the suggestion for a named tool. */
    private List<ScopeGroup> resolveGroups(Asks.Login req) {
        var out = new ArrayList<ScopeGroup>();
        if (req.groups() != null) {
            for (var id : req.groups()) {
                Groups.byId(id).ifPresentOrElse(out::add, () -> {
                    if (Profiles.known().contains(id)) {
                        Profiles.suggested(id).forEach(g -> Groups.byId(g).ifPresent(out::add));
                    }
                });
            }
        }
        if (req.customScopes() != null && !req.customScopes().isEmpty()) {
            var id = req.customId() == null || req.customId().isBlank() ? "custom" : req.customId();
            out.add(ScopeGroup.handWritten(id, req.customScopes()));
        }
        return out.stream().distinct().toList();
    }

    /**
     * Migration for pre-wallet stores. The user has said he would rather re-consent than migrate, so
     * this stays available but deliberately unelaborated: what it finds becomes one {@code legacy}
     * token per account, carrying exactly the scopes that token really had.
     */
    public String importOld(Asks.Import req) throws IOException {
        require();
        var profiles = req.profiles() == null || req.profiles().isEmpty() ? Profiles.known() : req.profiles();
        var org = core.keyring.org(req.org()).orElseGet(() -> {
            var fresh = new OrgRecord(req.org());
            fresh.label = req.org();
            core.keyring.data().orgs().add(fresh);
            return fresh;
        });

        var best = new LinkedHashMap<String, Found>();
        var visited = new ArrayList<Path>();
        for (var profile : profiles) {
            var root = req.root() != null && !req.root().isBlank()
                    ? Path.of(req.root()) : Profiles.legacyRoot(profile);
            if (!visited.contains(root)) visited.add(root);
            var wanted = req.accounts() == null || req.accounts().isEmpty()
                    ? ImportOldStore.accounts(root) : req.accounts();
            for (var account : wanted) {
                var found = ImportOldStore.read(root, account, req.appKey(), legacyScopes(profile));
                if (found == null) continue;
                best.merge(account, found, (a, b) -> b.scopes().size() > a.scopes().size() ? b : a);
                if (org.credentialsJson == null) adoptClient(org, found.credentialsJson());
                core.keyring.claimDomain(org, account);
            }
        }

        var taken = new ArrayList<String>();
        for (var e : best.entrySet()) {
            var existing = core.keyring.find(e.getKey(), LEGACY).orElse(null);
            if (existing != null && existing.scopes().size() >= e.getValue().scopes().size()) continue;
            if (existing != null) core.keyring.data().credentials().remove(existing);
            var rec = new CredentialRecord(e.getKey(), org.id, LEGACY);
            rec.refreshToken = e.getValue().refreshToken();
            rec.scopes = List.copyOf(e.getValue().scopes());
            rec.order = 1000;
            core.keyring.data().credentials().add(rec);
            taken.add(e.getKey());
        }
        core.keyring.save();
        if (req.deleteOld()) deleteOldStores(visited, best.keySet(), req.appKey());
        return Json.of(Map.of("org", org.id, "imported", taken,
                "note", "imported as 'legacy' tokens. Re-consent into proper groups when convenient.",
                "rootsScanned", visited.stream().map(Path::toString).toList()));
    }

    /** The scope set each old tool consented under, needed only to open its stored token. */
    private static List<String> legacyScopes(String profile) {
        return switch (profile) {
            case Profiles.GSHEETS -> List.of("https://www.googleapis.com/auth/spreadsheets",
                    "https://www.googleapis.com/auth/drive");
            case Profiles.GMAIL -> List.of("https://www.googleapis.com/auth/gmail.modify",
                    "https://www.googleapis.com/auth/gmail.compose");
            case Profiles.GSLIDES -> List.of("https://www.googleapis.com/auth/presentations",
                    "https://www.googleapis.com/auth/drive.readonly",
                    "https://www.googleapis.com/auth/drive.file");
            default -> List.of("https://www.googleapis.com/auth/drive");
        };
    }

    /**
     * Two verbs on purpose. The token-only form is short-lived and safe to relay; the raw form is the
     * escape hatch, deliberately awkward and recorded — a tool that forbids its owner gets bypassed by
     * its owner, and then there is neither security nor a record.
     */
    public String export(Asks.Export req) throws IOException {
        require();
        var held = core.keyring.tokensFor(req.account());
        if (held.isEmpty()) throw new IOException("no tokens for " + req.account());
        var cred = held.getFirst();
        var org = core.keyring.org(cred.orgId)
                .orElseThrow(() -> new IOException("no OAuth client stored for org '" + cred.orgId + "'"));
        core.audit.record(new AuditEvent(System.currentTimeMillis(), "wallet", req.account(), "wallet",
                req.raw() ? "EXPORT RAW CREDENTIAL" : "export access token", uskoag.wallet.wire.Tier.DESTRUCTIVE,
                Verdict.ALLOW, 1, "cli", ProcessHandle.current().pid(), req.account(), "wallet export"));
        if (!req.raw()) {
            return Json.of(Map.of("accessToken", core.tokens.accessToken(cred, org),
                    "group", cred.group, "expiresInSeconds", 3600));
        }
        Log.warn("RAW CREDENTIAL EXPORTED for " + cred.account);
        return Json.of(Map.of("account", cred.account, "org", cred.orgId, "group", cred.group,
                "clientId", org.clientId, "clientSecret", org.clientSecret,
                "refreshToken", cred.refreshToken, "scopes", cred.scopes()));
    }

    /** Editing the patterns is how a second domain arrives; one client routinely serves several. */
    public String setDomains(Asks.OrgDomains req) throws IOException {
        require();
        var org = core.keyring.org(req.id())
                .orElseThrow(() -> new IOException("no such org: " + req.id()));
        var bad = new ArrayList<String>();
        if (req.domains() != null) {
            req.domains().forEach(d -> {
                var why = uskoag.wallet.wire.DomainRule.problem(d);
                if (why != null && !d.isBlank()) bad.add(d + " (" + why + ")");
            });
        }
        if (!bad.isEmpty()) return Json.of(Asks.Done.no("not saved: " + String.join("; ", bad)));
        org.setDomains(req.domains());
        core.keyring.save();
        return Json.of(Asks.Done.yes(org.id + " now answers for: "
                + (org.domains().isEmpty() ? "(nothing - accounts must name --org)"
                : String.join(", ", org.domains()))));
    }

    /**
     * Renames a client and re-points every account at it in the same step.
     *
     * <p>The id is a foreign key, not a label — every {@link CredentialRecord} carries it — so renaming
     * without carrying the accounts would leave them pointing at a client that no longer exists, and
     * their tokens would fail to refresh at the next hour boundary with nothing obvious to blame.
     */
    public String renameOrg(Asks.OrgRename req) throws IOException {
        require();
        if (req.to() == null || req.to().isBlank()) return Json.of(Asks.Done.no("give it a new id"));
        var org = core.keyring.org(req.from())
                .orElseThrow(() -> new IOException("no such client: " + req.from()));
        if (core.keyring.org(req.to()).isPresent()) {
            return Json.of(Asks.Done.no("'" + req.to() + "' already exists - pick another id"));
        }
        var moved = 0;
        for (var c : core.keyring.data().credentials()) {
            if (req.from().equalsIgnoreCase(c.orgId)) {
                c.orgId = req.to();
                moved++;
            }
        }
        var wasLabel = org.id.equals(org.label);
        org.id = req.to();
        if (wasLabel) org.label = req.to();
        core.keyring.save();
        if (core.settings.backupCredentialsJson && org.credentialsJson != null) {
            CredentialsBackup.save(org.id, org.credentialsJson);
            CredentialsBackup.forget(req.from());
        }
        return Json.of(Asks.Done.yes("renamed '" + req.from() + "' to '" + org.id + "', "
                + moved + " token(s) re-pointed"));
    }

    /**
     * Removing an OAuth client leaves its accounts unable to refresh, so this refuses while any remain
     * rather than producing tokens that fail at the next hour boundary with no obvious cause.
     */
    public String removeOrg(Asks.OrgRef req) throws IOException {
        require();
        var using = core.keyring.data().credentials().stream()
                .filter(c -> req.id().equalsIgnoreCase(c.orgId)).map(c -> c.account).distinct().toList();
        if (!using.isEmpty()) {
            return Json.of(Asks.Done.no("still used by " + using
                    + " - forget those accounts first, or their tokens would stop refreshing"));
        }
        var gone = core.keyring.data().orgs().removeIf(o -> o.id.equalsIgnoreCase(req.id()));
        if (gone) core.keyring.save();
        return Json.of(gone ? Asks.Done.yes("org '" + req.id() + "' removed") : Asks.Done.no("no such org"));
    }

    public String forget(Asks.Forget req) throws IOException {
        require();
        var gone = core.keyring.data().credentials().removeIf(c -> c.account.equalsIgnoreCase(req.account()));
        if (gone) {
            core.keyring.save();
            core.tokens.clear();
        }
        return Json.of(gone ? Asks.Done.yes("all tokens for " + req.account() + " removed from the wallet")
                : Asks.Done.no("no such account"));
    }

    private static void adoptClient(OrgRecord org, String credentialsJson) {
        try {
            var secrets = ClientJson.parse(credentialsJson);
            org.credentialsJson = credentialsJson;
            org.clientId = secrets.get("client_id");
            org.clientSecret = secrets.get("client_secret");
        } catch (IOException e) {
            Log.warn("could not read the OAuth client during import - " + e);
        }
    }

    private static String domainOf(String email) {
        var at = email == null ? -1 : email.indexOf('@');
        return at < 0 ? "<domain>" : email.substring(at + 1);
    }

    private void require() throws IOException {
        if (!core.keyring.unlocked()) throw new IOException("wallet is locked");
    }

    private static void deleteOldStores(List<Path> roots, java.util.Set<String> accounts, String appKey) {
        var dirName = "tokens_" + Md5.hex(appKey);
        for (var root : roots) {
            for (var account : accounts) {
                var dir = root.resolve(account).resolve(dirName);
                if (!Files.isDirectory(dir)) continue;
                try (var paths = Files.walk(dir)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                        }
                    });
                    Log.info("deleted old token store " + dir);
                } catch (IOException e) {
                    Log.warn("could not delete " + dir + " - " + e);
                }
            }
        }
    }
}

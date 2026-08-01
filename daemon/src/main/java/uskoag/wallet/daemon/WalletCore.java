package uskoag.wallet.daemon;

import uskoag.wallet.wire.AccessGrant;
import uskoag.wallet.wire.AccessRequest;
import uskoag.wallet.wire.AccountInfo;
import uskoag.wallet.wire.GApi;
import uskoag.wallet.wire.Groups;
import uskoag.wallet.wire.OrgInfo;
import uskoag.wallet.wire.TokenInfo;
import uskoag.wallet.wire.WalletStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Everything the wallet is, assembled: keyring, policy, tokens, grants, audit. No UI, no transport. */
public final class WalletCore {

    public final Keyring keyring = new Keyring();

    /**
     * Defaults until the keyring is opened, then the stored values are copied <em>into</em> this same
     * object. The identity is shared with {@link PolicyEngine}, so it must never be replaced — and the
     * defaults being the strict ones means a locked wallet is never the permissive one.
     */
    public final WalletSettings settings = new WalletSettings();

    public final PolicyEngine policy = new PolicyEngine(keyring, settings);
    public final TokenCache tokens = new TokenCache();
    public final Grants grants = new Grants();
    public final Audit audit = new Audit();
    public final ResourceNames names = new ResourceNames();

    private static final java.util.concurrent.ScheduledExecutorService TIMER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "wallet-autolock");
                t.setDaemon(true);
                return t;
            });

    private ApprovalGateway gateway = new HeadlessGateway();
    private volatile int proxyPort;
    private volatile long lastActivity = System.currentTimeMillis();
    private java.util.concurrent.ScheduledFuture<?> autoLock;

    public void gateway(ApprovalGateway g) {
        this.gateway = g;
    }

    public ApprovalGateway gateway() {
        return gateway;
    }

    public void proxyPort(int port) {
        this.proxyPort = port;
    }

    public int proxyPortValue() {
        return proxyPort;
    }

    public void unlock(char[] passphrase) throws java.io.IOException {
        if (keyring.exists()) keyring.unlock(passphrase);
        else keyring.create(passphrase);
        settings.copyFrom(keyring.data().settings());
        audit.open(keyring.auditKey());

        // The wallet used to keep a plain copy of each credentials.json beside the keyring as a
        // forgotten-passphrase safety net. It no longer does, and any left from before are removed here
        // rather than left to rot: the office keeps its own copies, so the net was buying nothing and
        // costing a set of OAuth client secrets sitting outside the encryption.
        var swept = CredentialsBackup.purge();
        if (swept > 0) Log.info("removed " + swept + " plain credentials.json backup(s) from earlier"
                + " versions; the wallet no longer keeps any");

        armAutoLock();
        Log.info("unlocked: " + keyring.data().orgs().size() + " org(s), "
                + keyring.accountNames().size() + " account(s), "
                + keyring.data().credentials().size() + " token(s), "
                + keyring.data().rules().size() + " rule(s)");
    }

    /** Total, not gradual: every live handle dies here, so anything in flight stops mid-call. */
    public void lock() {
        if (autoLock != null) {
            autoLock.cancel(false);
            autoLock = null;
        }
        grants.clear();
        tokens.clear();
        names.clear();
        audit.close();
        keyring.lock();
        Log.info("locked");
    }

    /**
     * Marks the wallet as in use, deferring the auto-lock. Called on every grant and every proxied call.
     *
     * <p>Idle means idle for the wallet, not for the screen: a batch running unattended for two hours is
     * not idle, and locking underneath it would fail the work for no gain.
     */
    public void touch() {
        lastActivity = System.currentTimeMillis();
    }

    /**
     * The second lock, and the reason it is worth having: an approval says <em>what</em> may be touched,
     * and this says <em>for how long anything at all</em> may be. Even a standing permission is useless
     * against a wallet that has locked itself, so an unattended machine converges on safe rather than
     * staying wherever the last approval left it. Someone who genuinely wants long unattended access
     * raises the number, which is a decision they made rather than a default they inherited.
     */
    private void armAutoLock() {
        if (autoLock != null) autoLock.cancel(false);
        var minutes = settings.autoLockMinutes;
        if (minutes <= 0) {
            Log.info("auto-lock disabled; this wallet stays unlocked until told otherwise");
            return;
        }
        touch();
        var idleMs = minutes * 60_000L;
        // Checked every fraction of the window rather than scheduled once, so editing the setting takes
        // effect within a minute instead of at the next unlock.
        var tick = Math.max(15_000L, idleMs / 6);
        autoLock = TIMER.scheduleWithFixedDelay(() -> {
            try {
                if (!keyring.unlocked()) return;
                if (System.currentTimeMillis() - lastActivity < settings.autoLockMinutes * 60_000L) return;
                Log.info("auto-locking after " + settings.autoLockMinutes + " idle minute(s)");
                lock();
                gateway.locked();
            } catch (Exception e) {
                Log.warn("auto-lock check failed: " + e);
            }
        }, tick, tick, java.util.concurrent.TimeUnit.MILLISECONDS);
        Log.info("auto-lock armed at " + minutes + " idle minute(s)");
    }

    public WalletStatus status() {
        var notes = new ArrayList<String>();
        if (!Dpapi.available()) notes.add("DPAPI unavailable, keyring is passphrase-only: " + Dpapi.unavailableReason());
        if (!gateway.interactive()) notes.add("no display, approvals will be denied rather than asked");
        var debug = Debug.note();
        if (debug != null) notes.add(debug);
        return new WalletStatus("1.0", keyring.unlocked(), keyring.exists(),
                keyring.unlocked() ? keyring.accountNames().size() : -1,
                0, proxyPort, notes);
    }

    public List<OrgInfo> orgs() {
        if (!keyring.unlocked()) return List.of();
        return keyring.data().orgs().stream()
                .map(o -> new OrgInfo(o.id, o.label, o.clientId, o.owner, o.domains(),
                        (int) keyring.accountNames().stream()
                                .filter(a -> keyring.anyFor(a).map(c -> o.id.equalsIgnoreCase(c.orgId)).orElse(false))
                                .count(),
                        o.addedAt))
                .toList();
    }

    public List<AccountInfo> accounts() {
        if (!keyring.unlocked()) return List.of();
        return keyring.accountNames().stream()
                .map(a -> new AccountInfo(a, keyring.anyFor(a).map(c -> c.orgId).orElse(null), tokensOf(a)))
                .toList();
    }

    public List<TokenInfo> tokensOf(String account) {
        return keyring.tokensFor(account).stream().map(c -> {
            var g = Groups.byId(c.group);
            return new TokenInfo(c.account, c.group,
                    g.map(x -> x.label()).orElse(c.group),
                    g.map(x -> x.detail()).orElse("hand-written scope set"),
                    c.scopes(), g.map(x -> x.tier()).orElse(uskoag.wallet.wire.Tier2.RESTRICTED),
                    c.addedAt, c.lastUsed, c.useCount, c.order);
        }).toList();
    }

    /**
     * Issues a handle for one tool run. It deliberately does not choose a token: which token serves a
     * call depends on the API and the tier of that individual call, which is only knowable at the proxy.
     */
    public AccessGrant access(AccessRequest req) {
        if (!keyring.unlocked()) {
            gateway.unlockNeeded(req.appName() + " is asking for " + req.account());
            return AccessGrant.failed("wallet is locked, unlock it and retry");
        }
        touch();
        var account = resolve(req.account());
        if (account.isEmpty()) return AccessGrant.failed(noAccount(req.account()));

        var api = GApi.of(req.api());
        var g = grants.issue(account.get(), req.profile(), req.appName(), api.alias,
                req.session(), req.pid(), req.peerCommand());
        return new AccessGrant(g.token(), "http://127.0.0.1:" + proxyPort + "/g/" + api.alias + "/",
                account.get(), g.correlationCode(), 0L, null);
    }

    /**
     * Writes the live settings back into the keyring, which is the only place they are kept.
     *
     * <p>Only the wallet's own Settings tab calls this. There is deliberately no verb for it: a process
     * able to set {@code readRequiresRule = false} over the socket would have made every other control
     * in here decorative.
     */
    public void saveSettings() throws java.io.IOException {
        keyring.data().settings().copyFrom(settings);
        keyring.save();
        Log.info("settings updated");
    }

    /**
     * Names a resource the way the proxy would, and refuses when the account holds no token that could
     * serve it.
     *
     * <p>Consent before resource, in that order. A standing rule naming a document the account cannot
     * open is a rule whose only possible future is a puzzling failure at Google, so it is refused here
     * where the reason can still be stated plainly.
     *
     * @throws java.io.IOException naming which consent is missing, in the words that fix it
     */
    public ResourceNames.Named nameFor(GApi api, String id, String account, uskoag.wallet.wire.Tier tier)
            throws java.io.IOException {
        var held = keyring.tokensFor(account);
        var chosen = TokenPicker.pick(held, api.alias, tier)
                .orElseThrow(() -> new java.io.IOException(TokenPicker.explain(account, api.alias, tier, held)));
        var org = keyring.org(chosen.orgId).orElse(null);
        return names.resolve(api, id, account, tokens.accessToken(chosen, org));
    }

    /** The named account, else the only one there is. Email is rarely typed when there is no ambiguity. */
    Optional<String> resolve(String account) {
        var all = keyring.accountNames();
        if (account != null && !account.isBlank()) {
            return all.stream().filter(a -> a.equalsIgnoreCase(account)).findFirst();
        }
        return all.size() == 1 ? Optional.of(all.getFirst()) : Optional.empty();
    }

    private String noAccount(String asked) {
        var all = keyring.accountNames();
        if (asked != null && !asked.isBlank()) {
            return "no credential for '" + asked + "'. Add its org and log in: uskoag-walletcli org add"
                    + " <org> --file credentials.json, then uskoag-walletcli login " + asked;
        }
        if (all.isEmpty()) return "the wallet has no accounts yet. Start with: uskoag-walletcli org add"
                + " <org> --file credentials.json";
        return "several accounts are stored, so the tool has to say which: pass --email. Known: " + all;
    }
}

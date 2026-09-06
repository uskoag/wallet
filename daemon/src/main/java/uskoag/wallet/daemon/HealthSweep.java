package uskoag.wallet.daemon;

import uskoag.wallet.wire.Health;
import uskoag.wallet.wire.HealthReport;
import uskoag.wallet.wire.HealthRow;
import uskoag.wallet.wire.Tier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The daily readonly ping over every credential in the keyring, and the record of when it last ran.
 *
 * <p>Every credential, not every account: a read-only mail token and a read-and-write one on the same
 * mailbox are two consents with two expiries, so they are two checks. That separation is the reason the
 * wallet holds several tokens per account in the first place, and a health check that collapsed them would
 * report the account as fine while the half of it nobody used that week was already dead.
 *
 * <p>The wallet has to be unlocked, because everything this reads is inside the keyring. That awkwardness
 * is the requirement's, not the design's, and it is handled by asking for the passphrase with the reason
 * stated — see {@code HealthDaily} in the UI module.
 */
public final class HealthSweep {

    /**
     * Due when the last completed sweep is older than this. Under a day on purpose: at exactly 24 hours
     * the slot marches forward through the working day and eventually lands at 3am, where nobody is
     * present to type a passphrase.
     */
    static final long DUE_MS = 20 * 3_600_000L;

    /** How long a dismissed or timed-out prompt buys before asking again. */
    static final long ASK_BACKOFF_MS = 6 * 3_600_000L;

    private final WalletCore core;
    private final Object lock = new Object();
    private volatile HealthReport last;

    public HealthSweep(WalletCore core) {
        this.core = core;
    }

    public boolean due() {
        return !HealthMarker.load().sweptWithin(DUE_MS);
    }

    /** Due, and we have not already put a passphrase box on screen recently for it. */
    public boolean shouldAskForPassphrase() {
        var marker = HealthMarker.load();
        return !marker.sweptWithin(DUE_MS) && !marker.askedWithin(ASK_BACKOFF_MS);
    }

    /** Records that the passphrase was asked for, whether or not it was typed. */
    public void asked() {
        var marker = HealthMarker.load();
        marker.lastPromptAt = System.currentTimeMillis();
        marker.save();
    }

    /** The most recent run in this process, for a caller that wants what happened without repeating it. */
    public HealthReport lastRun() {
        return last;
    }

    /** The stored verdicts, reported rather than re-tested. */
    public HealthReport stored() {
        if (!core.keyring.unlocked()) return new HealthReport(0, List.of());
        return new HealthReport(0, core.keyring.data().credentials().stream()
                .map(HealthCheck::row).toList());
    }

    /**
     * Runs the check and writes what it found into the keyring.
     *
     * @param account narrow to one account, or blank for all
     * @param group   narrow to one group, or blank for all of the chosen account's
     * @throws IOException when the wallet is locked, which is the one condition a caller can fix
     */
    public HealthReport run(String account, String group) throws IOException {
        if (!core.keyring.unlocked()) throw new IOException("wallet is locked");
        synchronized (lock) {
            var whole = blank(account) && blank(group);
            var checked = new ArrayList<HealthRow>();
            var changes = new ArrayList<String>();
            for (var cred : List.copyOf(core.keyring.data().credentials())) {
                if (!blank(account) && !cred.account.equalsIgnoreCase(account)) continue;
                if (!blank(group) && !cred.group.equalsIgnoreCase(group)) continue;
                var org = core.keyring.org(cred.orgId).orElse(null);
                var was = cred.health();
                if (HealthCheck.check(cred, org)) {
                    changes.add(cred.account + " / " + cred.group + ": " + was + " -> " + cred.health());
                    noteTransition(cred, org, was);
                }
                checked.add(HealthCheck.row(cred));
            }
            core.keyring.save();
            var report = new HealthReport(System.currentTimeMillis(), checked);
            last = report;
            if (whole) {
                var marker = HealthMarker.load();
                marker.lastSweepAt = System.currentTimeMillis();
                marker.save();
            }
            Log.info("credential health: " + report.headline()
                    + (changes.isEmpty() ? "" : "  changed: " + String.join("; ", changes)));
            return report;
        }
    }

    /**
     * A change of state is worth a line in the audit; a healthy credential found healthy again is not.
     *
     * <p>The death itself is also recorded on the OAuth client, and that is the only route to a fact
     * Google publishes nowhere: a Cloud project still in Testing status expires every refresh token seven
     * days after issuing it. Two or three deaths at the same number of days is the diagnosis, and
     * {@link uskoag.wallet.wire.OrgInfo#expiryHint} is where it gets said out loud.
     */
    private void noteTransition(CredentialRecord cred, OrgRecord org, Health was) {
        if (cred.health().bad() && !was.bad() && org != null) org.died(cred.lifeDaysAtDeath());
        core.audit.record(new AuditEvent(System.currentTimeMillis(), "wallet", cred.account, "wallet",
                "credential health " + was + " -> " + cred.health() + ": " + cred.group,
                Tier.READ, Verdict.ALLOW, 1, "daily health check",
                ProcessHandle.current().pid(), cred.group, "uskoag-wallet daily health check", null));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

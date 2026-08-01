package uskoag.wallet.daemon;

import uskoag.wallet.wire.Tier;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Development mode: lets the wallet be driven with nobody at the keyboard.
 *
 * <p>It exists because every control in here can only be tested by exercising it, and exercising it
 * needs a person to type a passphrase and click a dialog. Four sessions of work accumulated a pile of
 * built-but-never-run behaviour for exactly that reason, and the four bugs that live testing eventually
 * found were all invisible from the source. A thing that cannot be tested unattended does not get
 * tested.
 *
 * <p><b>This is a real reduction in protection while it is on, and it is meant to be.</b> Anything that
 * can switch off a control has to be as protected as the control, so the honest framing is not "safe
 * debug mode" — there is no such thing — but "a deliberately narrow, loud, self-closing window".
 * Four separate things bound it:
 *
 * <ol>
 *   <li><b>Two independent conditions, neither sufficient alone.</b> The passphrase comes from
 *       {@link #PASSPHRASE_VAR} in the environment, and {@code --debug} must be on the command line of
 *       the launch. A stray environment variable therefore changes nothing about an ordinary
 *       double-click launch, and the flag alone unlocks nothing. This is a guard against accident, not
 *       against an attacker — malware running as this user can set both. Said plainly because the
 *       distinction matters when deciding whether to leave it on.</li>
 *   <li><b>Tiered, and irreversible work is opt-in separately.</b> {@link #APPROVE_VAR} names which
 *       tiers may be auto-approved. Unset means read and mutate only: everything irreversible — a
 *       delete, a move, a share — still stops and asks a human. Testing the destructive path is a
 *       second, deliberate decision rather than something inherited by turning debug on.</li>
 *   <li><b>It leaves no residue.</b> A debug approval is always {@code once}: it never writes a
 *       standing rule, so nothing survives the run and closing debug mode does not require hunting
 *       down permissions it granted along the way.</li>
 *   <li><b>It closes itself.</b> Past {@link #EXPIRES} the flag is refused whatever the environment
 *       says, and the wallet carries on in normal mode. Reopening is a one-line date change and a
 *       rebuild — trivial for whoever is developing this, and not something that can be left on by
 *       forgetting about it. That is the answer to "neither permanently open nor permanently closed".</li>
 * </ol>
 *
 * <p>Two further properties worth stating because they are easy to lose in a later edit: the passphrase
 * is read once, never logged, never echoed to the audit and never held after the unlock; and debug mode
 * unlocks at <em>startup only</em>. It deliberately does not re-unlock on demand, because a wallet that
 * unlocks itself whenever something wants it has no lock, and because the lock-mid-run behaviour is
 * itself one of the things that needs testing.
 */
public final class Debug {

    /**
     * The date the flag stops working. Deliberately a constant in the source rather than a setting:
     * a setting can be changed by anything that can write settings, and the point of this one is that
     * extending it requires a rebuild.
     */
    private static final LocalDate EXPIRES = LocalDate.of(2026, 9, 30);

    /** Set only in the shell that launches the wallet. A machine-wide value is inherited by every process. */
    public static final String PASSPHRASE_VAR = "UKAG_WALLET_DEBUG_PASSPHRASE";

    /** Comma-separated: {@code read}, {@code mutate}, {@code destructive}, or {@code all}. */
    public static final String APPROVE_VAR = "UKAG_WALLET_DEBUG_APPROVE";

    /**
     * A ceiling on auto-approvals for the life of the process, in the spirit of counts before clocks:
     * a window bounds a person's sitting and does not bound a loop. A test that needs more than this
     * has stopped being a test.
     */
    private static final int MAX_AUTO_APPROVALS = 500;

    private static final AtomicInteger approved = new AtomicInteger();

    private static volatile boolean on;
    private static volatile Set<Tier> tiers = Set.of();

    private Debug() {
    }

    /**
     * Decides once, at startup, whether debug mode applies, and says why whenever it does not. Silence
     * about a refused {@code --debug} would send someone looking for a bug in the wallet.
     *
     * @return the passphrase to unlock with, or null to carry on normally. The caller must zero it.
     */
    public static char[] arm(boolean flagGiven) {
        if (!flagGiven) return null;

        var today = LocalDate.now();
        if (today.isAfter(EXPIRES)) {
            Log.warn("--debug expired on " + EXPIRES + " and is refused. This is by design: it closes"
                    + " itself so it cannot be left on by being forgotten. To reopen it, move EXPIRES in"
                    + " Debug.java and rebuild. Continuing in normal mode.");
            return null;
        }

        var pass = System.getenv(PASSPHRASE_VAR);
        if (pass == null || pass.isBlank()) {
            Log.warn("--debug given but " + PASSPHRASE_VAR + " is not set in this process's environment,"
                    + " so there is nothing to unlock with. Set it in the shell that launches the wallet"
                    + " — not machine-wide, which every child process would inherit. Continuing in normal"
                    + " mode.");
            return null;
        }

        tiers = parseTiers(System.getenv(APPROVE_VAR));
        on = true;

        Log.warn("############ DEBUG MODE ############");
        Log.warn("unlocking from " + PASSPHRASE_VAR + " with no dialog, and auto-approving: "
                + (tiers.isEmpty() ? "nothing — approvals still ask" : tiers)
                + ". Nothing is remembered: every debug approval is once-only and writes no standing"
                + " rule. Ceiling " + MAX_AUTO_APPROVALS + " approvals for this process; expires " + EXPIRES + ".");
        if (tiers.contains(Tier.DESTRUCTIVE)) {
            Log.warn("DESTRUCTIVE is auto-approved. Deletes, moves and shares will happen with nobody"
                    + " asked. Do not leave this wallet running on real work.");
        }
        Log.warn("####################################");
        return pass.toCharArray();
    }

    /**
     * Read and mutate by default, so turning debug on does not silently also turn on unattended
     * deleting. {@code all} is the explicit way to ask for that.
     */
    private static Set<Tier> parseTiers(String raw) {
        if (raw == null || raw.isBlank()) return Set.of(Tier.READ, Tier.MUTATE);
        var want = raw.toLowerCase(Locale.ROOT);
        if (want.contains("none")) return Set.of();
        if (want.contains("all")) return Set.of(Tier.READ, Tier.MUTATE, Tier.DESTRUCTIVE);
        var out = new java.util.HashSet<Tier>();
        for (var t : Tier.values()) {
            if (want.contains(t.name().toLowerCase(Locale.ROOT))) out.add(t);
        }
        return out;
    }

    public static boolean on() {
        return on;
    }

    /**
     * Whether this particular question may be answered without a person.
     *
     * <p>The ceiling is checked here rather than at arm time so that hitting it degrades into ordinary
     * prompting — a runaway loop stops being invisible instead of stopping the wallet.
     */
    public static boolean autoApproves(Tier tier) {
        if (!on || !tiers.contains(tier)) return false;
        if (approved.incrementAndGet() > MAX_AUTO_APPROVALS) {
            Log.warn("debug auto-approval ceiling of " + MAX_AUTO_APPROVALS + " reached; from here on"
                    + " every request asks a person, as it would without --debug");
            return false;
        }
        return true;
    }

    /** Marks an audit line so a debug-approved call can never be mistaken for one a person agreed to. */
    public static String tag(String operation) {
        return on ? "[DEBUG] " + operation : operation;
    }

    /** For {@code status}, so nobody has to read the log to discover the wallet is in this state. */
    public static String note() {
        if (!on) return null;
        return "DEBUG MODE: unlocked from the environment, auto-approving "
                + (tiers.isEmpty() ? "nothing" : tiers.toString()) + ", " + approved.get() + " of "
                + MAX_AUTO_APPROVALS + " auto-approvals used. Expires " + EXPIRES + ".";
    }
}

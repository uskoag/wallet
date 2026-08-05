package uskoag.wallet.wire;

/**
 * The line is reversible versus irreversible, not read versus write.
 *
 * <p>A wrong cell value is an annoyance; a deleted folder is not. Sharing sits in {@link #DESTRUCTIVE}
 * even though revoking an ACL is technically a write, because un-sharing does not un-copy.
 */
public enum Tier {

    /** list, search, get, download, export. Silent while unlocked. The 95% path. */
    READ(60 * 24 * 7, 60 * 24),

    /** create, update, append, draft, add. Silent while unlocked, logged, rate-limited. */
    MUTATE(60 * 24, 60 * 4),

    /** delete, trash, move, re-parent, clear a large range, every permission change. Prompted and budgeted. */
    DESTRUCTIVE(60, 0);

    /**
     * The longest a permission of this tier may ever stand: a week to read, a day to change, an hour to do
     * anything irreversible.
     *
     * <p>No permission here is unbounded, and none of these is configurable. Both of those follow from the
     * same observation: extending costs one click or one passphrase, so a short ceiling is nearly free,
     * while a long one is only ever discovered later — and "forever" is never what anybody meant, it is
     * what the dialog made easiest. Removing the option is more honest than offering it and advising
     * against it.
     *
     * <p>A deliberate consequence: a long irreversible batch will reach this ceiling mid-run. Tools doing
     * bulk destructive work have to treat a refusal as "pause and ask again", not as a fatal error, or
     * they will stop half-finished. The operation budget already forces the same discipline.
     */
    public final int maxMinutes;

    /**
     * The longest a rule covering EVERY document may stand — a day to read anything, four hours to change
     * anything, and no such thing at all for the irreversible tier.
     *
     * <p>Write was one hour and he raised it to four on 2026-08-04, for an unattended run. That is his
     * call and the trade is worth stating rather than burying: four hours is longer than anybody watches,
     * so for most of such a window the only thing standing between a poisoned document and everything
     * these accounts can write is the audit, read afterwards. The irreversible tier is untouched, which is
     * what keeps the blast radius to "wrong content" rather than "gone".
     *
     * <p>Tighter than {@link #maxMinutes} because it is a different object. A per-document rule is bounded
     * by the document: the worst it can do is the worst that can be done to one file somebody looked at and
     * named. A blanket rule has no such bound, so the only thing standing between it and everything the
     * account can reach is the clock — and a clock is a weak bound stretched over a week.
     *
     * <p>His numbers, and the reason he asked for the mode in the first place is worth keeping in view: the
     * nagging was too much, so the answer had to be an actual open door rather than advice to click faster.
     * A door that opens wide is a door that closes soon.
     *
     * <p>Enforced in {@link uskoag.wallet.wire.Tier} only as a number; the enforcement is in
     * {@code PolicyEngine.expiryFor}, keyed on the rule naming no resource, so both the tray window and
     * {@code policy quiet} get it and so would any third route.
     */
    public final int blanketMaxMinutes;

    Tier(int maxMinutes, int blanketMaxMinutes) {
        this.maxMinutes = maxMinutes;
        this.blanketMaxMinutes = blanketMaxMinutes;
    }

    /** The ceiling that applies to this rule: a blanket one is held to the shorter clock. */
    public int ceiling(boolean blanket) {
        return blanket ? blanketMaxMinutes : maxMinutes;
    }

    public boolean atLeast(Tier other) {
        return ordinal() >= other.ordinal();
    }

    /** The spans worth offering for this tier: bounded, and never past the ceiling. */
    public boolean allows(Span span) {
        return allows(span, false);
    }

    public boolean allows(Span span, boolean blanket) {
        return span.minutes > 0 && span.minutes <= ceiling(blanket);
    }
}

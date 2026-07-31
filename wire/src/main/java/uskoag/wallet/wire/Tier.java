package uskoag.wallet.wire;

/**
 * The line is reversible versus irreversible, not read versus write.
 *
 * <p>A wrong cell value is an annoyance; a deleted folder is not. Sharing sits in {@link #DESTRUCTIVE}
 * even though revoking an ACL is technically a write, because un-sharing does not un-copy.
 */
public enum Tier {

    /** list, search, get, download, export. Silent while unlocked. The 95% path. */
    READ(60 * 24 * 7),

    /** create, update, append, draft, add. Silent while unlocked, logged, rate-limited. */
    MUTATE(60 * 24),

    /** delete, trash, move, re-parent, clear a large range, every permission change. Prompted and budgeted. */
    DESTRUCTIVE(60);

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

    Tier(int maxMinutes) {
        this.maxMinutes = maxMinutes;
    }

    public boolean atLeast(Tier other) {
        return ordinal() >= other.ordinal();
    }

    /** The spans worth offering for this tier: bounded, and never past the ceiling. */
    public boolean allows(Span span) {
        return span.minutes > 0 && span.minutes <= maxMinutes;
    }
}

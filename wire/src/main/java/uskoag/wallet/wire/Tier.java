package uskoag.wallet.wire;

/**
 * The line is reversible versus irreversible, not read versus write.
 *
 * <p>A wrong cell value is an annoyance; a deleted folder is not. Sharing sits in {@link #DESTRUCTIVE}
 * even though revoking an ACL is technically a write, because un-sharing does not un-copy.
 */
public enum Tier {

    /** list, search, get, download, export. Silent while unlocked. The 95% path. */
    READ,

    /** create, update, append, draft, add. Silent while unlocked, logged, rate-limited. */
    MUTATE,

    /** delete, trash, move, re-parent, clear a large range, every permission change. Prompted and budgeted. */
    DESTRUCTIVE;

    public boolean atLeast(Tier other) {
        return ordinal() >= other.ordinal();
    }
}

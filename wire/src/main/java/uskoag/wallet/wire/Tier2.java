package uskoag.wallet.wire;

/**
 * Google's own classification of a scope, which is what decides how alarming the consent screen looks
 * and how aggressively an unused refresh token is expired.
 *
 * <p>This is not our judgement of risk — it is Google's, and it is the thing that actually drives the
 * unverified-app warning, the hundred-user cap and the expiry. Showing it on every row is what lets a
 * person shrink a consent screen before triggering it.
 */
public enum Tier2 {

    NONE("non-sensitive", "#546e7a"),
    SENSITIVE("sensitive", "#ef6c00"),
    RESTRICTED("RESTRICTED", "#b71c1c");

    public final String label, colour;

    Tier2(String label, String colour) {
        this.label = label;
        this.colour = colour;
    }
}

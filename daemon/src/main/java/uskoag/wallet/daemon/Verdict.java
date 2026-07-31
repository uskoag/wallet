package uskoag.wallet.daemon;

/** What the policy engine concluded, before any human is involved. */
public enum Verdict {

    /** A live rule covers it, or the defaults say this is browsing. Nothing is shown to anyone. */
    ALLOW,

    /** Nothing covers it yet. Ask, and let the answer become a rule if the person says so. */
    PROMPT,

    /** Refused without asking: the wallet is locked, or a rule says no, and no dialog can change that. */
    DENY
}

package uskoag.wallet.wire;

/** How a rule's {@code resource} is compared against the one a request actually names. */
public enum Match {

    /** This document and no other. The everyday case, and what an approval dialog writes. */
    EXACT,

    /** Everything under a Drive folder path, so a whole tree can be approved once. */
    PREFIX,

    /** Any resource of this api. Deliberately awkward to type; the UI never writes it silently. */
    ANY;

    public boolean test(String rule, String actual) {
        return switch (this) {
            case ANY -> true;
            case EXACT -> rule.equals(actual);
            case PREFIX -> actual != null && actual.startsWith(rule);
        };
    }
}

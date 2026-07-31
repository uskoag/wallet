package uskoag.wallet.daemon;

import uskoag.wallet.wire.ResourceRef;
import uskoag.wallet.wire.Tier;

/**
 * What the proxy decided a request is, in the terms a person will be shown.
 *
 * @param operation the words that go into the dialog: "delete 12 files", not "PATCH"
 * @param itemCount how many things this one call affects, which is the difference between a prompt you
 *                  can wave through and one you must read
 */
public record Classification(Tier tier, String operation, ResourceRef resource, int itemCount) {

    public static Classification read(String op, ResourceRef r) {
        return new Classification(Tier.READ, op, r, 1);
    }

    public static Classification mutate(String op, ResourceRef r) {
        return new Classification(Tier.MUTATE, op, r, 1);
    }

    public static Classification destructive(String op, ResourceRef r, int count) {
        return new Classification(Tier.DESTRUCTIVE, op, r, count);
    }
}

package uskoag.wallet.ui;

import leasegate.core.access.Scope;
import uskoag.wallet.wire.ApprovalAsk;
import uskoag.wallet.wire.ResourceRef;
import uskoag.wallet.wire.Tier;

/**
 * What one approval hands over, in the wallet's terms: one Google account, at one tier and the tiers below
 * it, for whatever the anchored process starts. The request that prompted it (the operation and the
 * document) travels along so the window can say what was actually asked for.
 */
public record WalletScope(String account, Tier tier, String operation, int itemCount, ResourceRef resource,
                          String resourceKind, int ops) implements Scope<WalletScope> {

    static WalletScope of(ApprovalAsk ask, int destructiveOps) {
        return new WalletScope(ask.account(), ask.tier(), ask.operation(), ask.itemCount(), ask.resource(),
                ask.resourceKind(), ask.tier() == Tier.DESTRUCTIVE ? destructiveOps : -1);
    }

    @Override
    public boolean covers(WalletScope requested) {
        return tier.atLeast(requested.tier) && account != null && account.equalsIgnoreCase(requested.account);
    }

    @Override
    public WalletScope narrowedTo(WalletScope requested) {
        return requested;
    }

    /** The client never asks for a span; the window offers what the anchor and the tier allow. */
    @Override
    public Integer requestedMinutes() {
        return null;
    }

    /** One dimension narrowed, the account: every document and every Google service on it is reached. */
    @Override
    public int narrowness() {
        return 1;
    }
}

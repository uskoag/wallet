package uskoag.wallet.daemon;

import uskoag.wallet.wire.ResourceRef;

/**
 * Approving a mailbox per-message would be absurd, so the resource here is the mailbox itself and the
 * tier does the discriminating.
 *
 * <p>{@code messages.delete} and {@code batchDelete} bypass the trash entirely and are irreversible.
 * Settings changes sit alongside them for a different reason: an auto-forwarding rule is a standing
 * exfiltration channel, which is worse than any single deletion.
 */
public final class GmailRules {

    private GmailRules() {
    }

    public static Classification classify(RequestFacts f) {
        var res = mailbox();
        var path = f.path();

        if (f.reads()) return Classification.read("read mail", res);

        if (path.contains("/settings/")) {
            return Classification.destructive("change mailbox settings (forwarding, filters, delegation)", res, 1);
        }
        if (path.endsWith("/batchDelete")) {
            return Classification.destructive("permanently delete", res, BatchRequests.countArray(f.body(), "ids"));
        }
        if (f.is("DELETE")) {
            return Classification.destructive(path.contains("/labels/") ? "delete a label" : "permanently delete", res, 1);
        }
        if (path.endsWith("/trash")) return Classification.destructive("move to trash", res, 1);
        if (path.endsWith("/send")) return Classification.mutate("send mail", res);
        if (path.endsWith("/batchModify")) return Classification.mutate("relabel in bulk", res);
        if (path.contains("/drafts")) return Classification.mutate("draft mail", res);
        return Classification.mutate("modify mail", res);
    }

    static ResourceRef mailbox() {
        return new ResourceRef("gmail", "mailbox", "this mailbox");
    }
}

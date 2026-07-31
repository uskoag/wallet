package uskoag.wallet.wire;

import java.util.List;

public record WalletStatus(
        String version,
        boolean unlocked,
        boolean keyringExists,
        int accounts,
        long lockedAt,
        int proxyPort,
        List<String> notes) {
}

package uskoag.wallet.wire;

import java.util.List;

public record PolicyReply(boolean allowed, String verdict, String detail, List<PolicyRule> rules) {

    public static PolicyReply of(boolean allowed, String verdict, String detail) {
        return new PolicyReply(allowed, verdict, detail, List.of());
    }

    public static PolicyReply listing(List<PolicyRule> rules) {
        return new PolicyReply(true, "listed", rules.size() + " rule(s)", rules);
    }
}

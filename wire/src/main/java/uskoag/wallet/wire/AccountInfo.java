package uskoag.wallet.wire;

import java.util.List;

/**
 * One account and every token it holds.
 *
 * <p>The inventory the wallet makes explicit. Today it is implicit — scattered across directories,
 * discoverable only by listing folders, with login state unknowable because tokens are encrypted per
 * key. Being able to see it at all is a usability win independent of any security property.
 */
public record AccountInfo(String email, String org, List<TokenInfo> tokens) {

    public boolean hasAnyToken() {
        return tokens != null && !tokens.isEmpty();
    }

    public List<TokenInfo> unused() {
        return tokens == null ? List.of() : tokens.stream().filter(TokenInfo::neverUsed).toList();
    }
}

package uskoag.wallet.daemon;

import java.util.List;

/** One old token store, decrypted: what it authorises, and the OAuth client it was minted under. */
public record Found(String refreshToken, List<String> scopes, String credentialsJson) {
}

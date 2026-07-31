package uskoag.wallet.wire;

import java.util.List;

/**
 * What a client asks the wallet for.
 *
 * <p>{@code profile} is what the app-key used to be, minus the secrecy: a compiled-in constant naming
 * which credential to use. The tool <em>declares</em> it rather than the wallet inferring it from the
 * peer command line, because inference is spoofable and policy must not depend on it being truthful.
 */
public record AccessRequest(
        String api,
        String profile,
        String appName,
        String account,
        List<String> scopes,
        String session,
        long pid,
        String peerCommand) {
}

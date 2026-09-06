package uskoag.wallet.wire;

import java.util.List;

/**
 * What a client asks the wallet for.
 *
 * <p>{@code profile} is what the app-key used to be, minus the secrecy: a compiled-in constant naming
 * which credential to use. The tool <em>declares</em> it rather than the wallet inferring it from the
 * peer command line, because inference is spoofable and policy must not depend on it being truthful.
 *
 * <p>{@code caller} is the same shape of thing and carries the same warning in {@link CallerInfo}: it is
 * what the dialog shows a person, and it is never an input to a decision.
 */
public record AccessRequest(
        String api,
        String profile,
        String appName,
        String account,
        List<String> scopes,
        String session,
        long pid,
        CallerInfo caller,
        long sourcePid) {

    /**
     * The one thing a client says that the wallet is allowed to act on, and only because it can check it.
     *
     * <p>{@code sourcePid} names the process this run of work belongs to — the agent or the terminal, not
     * this short-lived invocation — so that one approval can cover the run. Everything else here is
     * unverifiable and is displayed rather than trusted; this is verifiable, because the wallet learns the
     * real calling process from the kernel and can confirm the declared pid is genuinely one of its
     * ancestors. A declaration that is not gets discarded and reported at both ends.
     *
     * <p>Zero means nothing was declared, which is the ordinary case and costs nothing: the wallet then
     * picks the anchor itself.
     */
    public AccessRequest {
        if (sourcePid < 0) sourcePid = 0;
    }
}

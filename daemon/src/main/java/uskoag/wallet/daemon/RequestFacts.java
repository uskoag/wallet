package uskoag.wallet.daemon;

/**
 * A request as the proxy sees it, which is the only place classification can honestly happen.
 *
 * <p>Asking the client to declare its own tier before the call would mean trusting the party we are
 * not trusting; here the request has already been made and cannot be described as anything other than
 * what it is.
 *
 * @param path the upstream path with the proxy's own {@code /g/<alias>/} prefix already stripped
 * @param body null for anything streamed as bytes — media never gets parsed, which is also why it is fast
 */
public record RequestFacts(String api, String method, String path, String query, byte[] body) {

    public boolean is(String verb) {
        return verb.equalsIgnoreCase(method);
    }

    public boolean reads() {
        return is("GET") || is("HEAD");
    }

    public String bodyText() {
        return body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    public boolean queryHas(String key) {
        return query != null && (query.startsWith(key + "=") || query.contains("&" + key + "="));
    }
}

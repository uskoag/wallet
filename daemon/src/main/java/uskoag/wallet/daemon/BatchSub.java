package uskoag.wallet.daemon;

/** One request inside a multipart batch envelope, as far as the request-line and no further. */
public record BatchSub(String method, String path, String query) {

    /** The sub-request as the classifier sees it: same api, its own verb and target, no body. */
    public RequestFacts facts(String api) {
        return new RequestFacts(api, method, path, query, null);
    }
}

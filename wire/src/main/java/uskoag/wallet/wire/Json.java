package uskoag.wallet.wire;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * One codec for the whole wire. HTML escaping is off because this output goes to terminals, pipes and
 * agents' stdin, never into a web page, and the default would render {@code <email>} unreadably.
 */
public final class Json {

    public static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private Json() {
    }

    public static String of(Object o) {
        return GSON.toJson(o);
    }

    public static <T> T to(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }
}

package uskoag.wallet.daemon;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.Map;

/** Pulls client_id and client_secret out of a credentials.json, whether it is installed or web flavour. */
public final class ClientJson {

    private ClientJson() {
    }

    public static Map<String, String> parse(String json) throws IOException {
        try {
            var root = JsonParser.parseString(json).getAsJsonObject();
            var details = root.has("installed") ? root.getAsJsonObject("installed")
                    : root.has("web") ? root.getAsJsonObject("web") : root;
            var id = details.get("client_id").getAsString();
            var secret = details.get("client_secret").getAsString();
            return Map.of("client_id", id, "client_secret", secret);
        } catch (Exception e) {
            throw new IOException("that does not look like a Google credentials.json — " + e.getMessage());
        }
    }
}

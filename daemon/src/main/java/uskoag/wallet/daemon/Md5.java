package uskoag.wallet.daemon;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * The one thing MD5 is still used for here: reproducing the {@code tokens_<md5(appKey)>} directory name
 * that the old token store was written under, so import can find and then delete it. Never for security.
 */
public final class Md5 {

    private Md5() {
    }

    public static String hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return s;
        }
    }
}

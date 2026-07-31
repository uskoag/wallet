package uskoag.wallet.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Help text lives in a resource rather than a string literal, per the repo's no-text-blocks rule. */
public final class Help {

    private Help() {
    }

    public static void print() {
        try (InputStream in = Help.class.getResourceAsStream("/help.txt")) {
            if (in == null) {
                System.out.println("uskoag-walletcli — help resource missing from the jar");
                return;
            }
            System.out.println(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.out.println("uskoag-walletcli — could not read help: " + e.getMessage());
        }
    }
}

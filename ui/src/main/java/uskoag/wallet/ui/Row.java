package uskoag.wallet.ui;

import uskoag.wallet.wire.TokenInfo;

/**
 * One line of the accounts table. {@code token} is null for account and placeholder rows, which is what
 * the buttons check before acting.
 */
public record Row(String kind, String account, String text, String client, TokenInfo token) {

    public static Row account(String email, String client) {
        return new Row("account", email, email, client == null ? "(none)" : client, null);
    }

    public static Row token(String email, TokenInfo t) {
        return new Row("token", email, t.label(), "", t);
    }

    public static Row note(String email, String text) {
        return new Row("empty", email, text, "", null);
    }

    @Override
    public String toString() {
        return text == null ? "" : text;
    }
}

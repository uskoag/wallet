package uskoag.wallet.daemon;

/** One cached Google access token and the moment it stops being usable. */
public record Live(String token, long expiresAt) {

    public boolean usableAt(long when) {
        return token != null && when < expiresAt;
    }
}

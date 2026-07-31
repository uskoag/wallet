package uskoag.wallet.daemon;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

/** AES-GCM with the IV prepended, and PBKDF2 for turning a typed passphrase into a key. */
public final class Aes {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12, TAG_BITS = 128, PBKDF2_ROUNDS = 210_000;

    private static final SecureRandom RNG = new SecureRandom();

    private Aes() {
    }

    public static byte[] salt() {
        var s = new byte[32];
        RNG.nextBytes(s);
        return s;
    }

    public static SecretKey derive(char[] passphrase, byte[] salt) {
        try {
            var spec = new PBEKeySpec(passphrase, salt, PBKDF2_ROUNDS, 256);
            var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("could not derive the master key", e);
        }
    }

    public static SecretKey randomKey() {
        var k = new byte[32];
        RNG.nextBytes(k);
        return new SecretKeySpec(k, "AES");
    }

    public static byte[] encrypt(byte[] plain, SecretKey key) {
        try {
            var iv = new byte[IV_LEN];
            RNG.nextBytes(iv);
            var c = Cipher.getInstance(CIPHER);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            var body = c.doFinal(plain);
            var out = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public static byte[] decrypt(byte[] blob, SecretKey key) {
        try {
            if (blob.length < IV_LEN) throw new IllegalArgumentException("ciphertext too short");
            var c = Cipher.getInstance(CIPHER);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Arrays.copyOf(blob, IV_LEN)));
            return c.doFinal(blob, IV_LEN, blob.length - IV_LEN);
        } catch (Exception e) {
            throw new IllegalStateException("decryption failed — wrong passphrase, or the file was altered", e);
        }
    }
}

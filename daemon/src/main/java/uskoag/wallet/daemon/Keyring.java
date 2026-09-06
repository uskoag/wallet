package uskoag.wallet.daemon;

import uskoag.wallet.wire.Json;
import uskoag.wallet.wire.WalletPaths;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * The keyring on disk and in memory. Two layers, both keyed on the same typed passphrase, so there is
 * still only one thing to type: AES-GCM under a PBKDF2 key, then DPAPI with entropy so the file is
 * inert on any other machine.
 */
public final class Keyring {

    private static final byte[] MAGIC = {'U', 'K', 'W', 'K', '1'};
    private static final int FLAG_DPAPI = 0x1, SALT_LEN = 32, HEADER = 8 + SALT_LEN;

    /**
     * Deliberately low, and it is not the thing doing the work. Length here only slows an attacker who
     * already holds the file, and to hold it they must already be on this machine as this user — at
     * which point section 2 of the design applies and a longer passphrase buys little. The controls
     * that matter are DPAPI binding the file to this machine, the approval gate, and the audit. A
     * minimum that makes the wallet annoying to unlock costs more than it returns.
     */
    public static final int MIN_PASSPHRASE = 6;

    private SecretKey master;
    private byte[] salt;
    private char[] passphrase;
    private KeyringData data;

    public boolean exists() {
        return Files.exists(WalletPaths.keyringFile());
    }

    public boolean unlocked() {
        return data != null;
    }

    public KeyringData data() {
        if (data == null) throw new IllegalStateException("wallet is locked");
        return data;
    }

    public synchronized void lock() {
        if (passphrase != null) Arrays.fill(passphrase, '\0');
        passphrase = null;
        master = null;
        data = null;
    }

    /** First run: mint a fresh keyring, including the audit's own column key. */
    public synchronized void create(char[] phrase) throws IOException {
        // Normalised here too, so a keyring is sealed under the same rule however it was created.
        var clean = normalise(phrase);
        if (clean.length < MIN_PASSPHRASE) {
            Arrays.fill(clean, '\0');
            throw new IOException("the passphrase must be at least " + MIN_PASSPHRASE + " letters."
                    + " Letters only, and case does not matter.");
        }
        salt = Aes.salt();
        master = Aes.derive(clean, salt);
        passphrase = clean;
        data = new KeyringData();
        data.auditKeyB64 = Base64.getEncoder().encodeToString(Aes.randomKey().getEncoded());
        save();
    }

    /**
     * Opens the keyring, accepting the passphrase as typed or folded to upper case.
     *
     * <p>New passphrases are letters only and case does not matter — they are normalised by
     * {@link #normalise} before the key is derived. Existing keyrings were sealed with whatever was
     * typed at the time, mixed case and all, and the bytes of the passphrase <em>are</em> the key: had
     * this simply started normalising, every keyring predating the change would have stopped opening,
     * with no diagnosis available beyond "wrong passphrase" and no way back except discarding the
     * keyring and re-consenting every account.
     *
     * <p>So it tries what was typed, then the normalised form. One of them is the passphrase this
     * keyring was actually sealed with. The cost is one extra key derivation on the path that was going
     * to fail anyway, and it converges the moment the passphrase is next changed.
     */
    public synchronized void unlock(char[] phrase) throws IOException {
        try {
            open(phrase);
        } catch (IOException | RuntimeException asTyped) {
            var folded = normalise(phrase);
            if (Arrays.equals(folded, phrase)) {
                Arrays.fill(folded, '\0');
                throw asTyped;
            }
            try {
                open(folded);
            } catch (IOException | RuntimeException alsoFolded) {
                throw asTyped;      // report the original failure, not the fallback's
            } finally {
                Arrays.fill(folded, '\0');
            }
        }
    }

    /**
     * Letters only, upper case — the form a passphrase is stored under from now on.
     *
     * <p>Case is dropped rather than merely allowed, because "it should not matter whether I type small
     * or capital" is only true if both derive the same key. Anything that is not a letter is dropped
     * too, so that setting a passphrase cannot produce one that needs a chord to type. Both rules exist
     * for the same reason: a passphrase typed several times a day by someone for whom modifier keys
     * hurt should cost no modifier keys.
     */
    public static char[] normalise(char[] phrase) {
        if (phrase == null) return new char[0];
        var out = new StringBuilder(phrase.length);
        for (var c : phrase) {
            if (Character.isLetter(c)) out.append(Character.toUpperCase(c));
        }
        var result = new char[out.length()];
        out.getChars(0, out.length(), result, 0);
        // The builder held the passphrase in the clear; do not leave it for the GC to get to eventually.
        out.setLength(0);
        return result;
    }

    private synchronized void open(char[] phrase) throws IOException {
        var raw = Files.readAllBytes(WalletPaths.keyringFile());
        if (raw.length < HEADER || !Arrays.equals(Arrays.copyOf(raw, MAGIC.length), MAGIC)) {
            throw new IOException("not a wallet keyring: " + WalletPaths.keyringFile());
        }
        var flags = raw[MAGIC.length];
        salt = Arrays.copyOfRange(raw, 8, HEADER);
        var payload = Arrays.copyOfRange(raw, HEADER, raw.length);
        if ((flags & FLAG_DPAPI) != 0) payload = Dpapi.unprotect(payload, entropy(phrase, salt));

        master = Aes.derive(phrase, salt);
        var json = new String(Aes.decrypt(payload, master), StandardCharsets.UTF_8);
        var read = Json.to(json, KeyringData.class);
        // A keyring written by a different version must fail loudly here. Gson would happily parse an
        // older shape into the current one and leave the fields that moved simply unset - credentials
        // with no org, say - which looks like a working wallet and is not one. Refusing is recoverable;
        // silently half-loading and then saving over the original is not.
        var migrated = Migrate.forward(read);
        if (read.version != null && !KeyringData.VERSION.equals(read.version) && !migrated) {
            master = null;
            throw new IOException("this keyring is version " + read.version + " and this wallet writes version "
                    + KeyringData.VERSION + ". Nothing was changed. Move " + WalletPaths.keyringFile().getFileName()
                    + " aside and start fresh, or run a wallet of the matching version to export first.");
        }
        data = read;
        passphrase = phrase.clone();
        if (migrated) save();
        if (data.auditKeyB64 == null) {
            data.auditKeyB64 = Base64.getEncoder().encodeToString(Aes.randomKey().getEncoded());
            save();
        }
    }

    public synchronized void save() throws IOException {
        if (data == null || master == null) throw new IllegalStateException("wallet is locked");
        var plain = Json.of(data).getBytes(StandardCharsets.UTF_8);
        var payload = Aes.encrypt(plain, master);
        var flags = 0;
        if (Dpapi.available()) {
            payload = Dpapi.protect(payload, entropy(passphrase, salt));
            flags |= FLAG_DPAPI;
        }
        var out = new byte[HEADER + payload.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        out[MAGIC.length] = (byte) flags;
        System.arraycopy(salt, 0, out, 8, salt.length);
        System.arraycopy(payload, 0, out, HEADER, payload.length);

        Files.createDirectories(WalletPaths.home());
        var tmp = WalletPaths.keyringFile().resolveSibling("keyring.bin.tmp");
        Files.write(tmp, out);
        Files.move(tmp, WalletPaths.keyringFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Changing the passphrase re-derives the master key and rewrites this one file. Nothing else moves:
     * the audit's column key lives inside the keyring rather than being derived from the passphrase, so
     * a year of history stays readable across as many passphrase changes as you like.
     */
    public synchronized void changePassphrase(char[] current, char[] fresh) throws IOException {
        if (data == null) throw new IllegalStateException("unlock the wallet before changing its passphrase");
        // The current one is checked as typed or normalised, for the same reason unlock accepts both:
        // this keyring may predate the rule and still be sealed with mixed case.
        if (!Arrays.equals(passphrase, current) && !Arrays.equals(passphrase, normalise(current))) {
            throw new IOException("current passphrase does not match");
        }

        // Normalised before anything is derived from it, so what is stored is what will be typed. Doing
        // this at the boundary rather than in the dialog means a passphrase set from the CLI obeys the
        // same rule as one set from the window, and neither can produce a keyring the other cannot open.
        var clean = normalise(fresh);
        if (clean.length < MIN_PASSPHRASE) {
            Arrays.fill(clean, '\0');
            throw new IOException("the new passphrase must be at least " + MIN_PASSPHRASE
                    + " letters. Letters only — digits and punctuation are ignored, and case does not"
                    + " matter, so it can be typed without a shift key.");
        }

        var previous = passphrase;
        salt = Aes.salt();
        master = Aes.derive(clean, salt);
        passphrase = clean.clone();
        save();
        Arrays.fill(clean, '\0');
        Arrays.fill(previous, '\0');
    }

    /**
     * Is this the passphrase this wallet is unlocked with?
     *
     * <p>Used to re-ask before an irreversible operation, and before the tray's open-access window issues
     * the widest grant the wallet can make. Compared against the copy already in memory rather than by
     * re-deriving the key, because the point is to prove a person is present, not to re-open the keyring —
     * and re-deriving would be a slow KDF run on the request path for no gain. Byte-wise constant time, so
     * a wrong answer leaks nothing about how much of it was right.
     *
     * <p><b>As typed or folded, exactly like {@link #unlock}.</b> Every keyring created or re-keyed since
     * passphrases became letters-only is sealed under {@link #normalise}, so the copy sitting in memory is
     * the folded form — upper case, letters only — whatever was actually typed to open it. Comparing the
     * typed passphrase against that on the nose refused the real passphrase and accepted only a string the
     * wallet has never shown anybody, which left both windows that re-ask impassable: the two boxes agreed,
     * the passphrase was right, and it kept answering that it was not.
     *
     * <p>Folded against the STORED value, never fold against fold. The latter is a different and worse
     * rule: a keyring predating normalisation may be sealed with digits or punctuation in it, and its
     * folded form is shorter than the secret — comparing two of those would admit passphrases that differ
     * everywhere except the letters.
     */
    public synchronized boolean verify(char[] phrase) {
        if (data == null || passphrase == null || phrase == null) return false;
        if (same(passphrase, phrase)) return true;
        var folded = normalise(phrase);
        try {
            return same(passphrase, folded);
        } finally {
            Arrays.fill(folded, '\0');
        }
    }

    /** Constant time, so a near miss is indistinguishable from a wild guess. */
    private static boolean same(char[] a, char[] b) {
        var mine = utf8(a);
        var theirs = utf8(b);
        try {
            return MessageDigest.isEqual(mine, theirs);
        } finally {
            Arrays.fill(mine, (byte) 0);
            Arrays.fill(theirs, (byte) 0);
        }
    }

    /** Via a CharBuffer rather than a String, so the passphrase never lands in the string pool. */
    private static byte[] utf8(char[] chars) {
        var encoded = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars));
        var out = new byte[encoded.remaining()];
        encoded.get(out);
        return out;
    }

    /** The audit's column key, available only while unlocked. */
    public SecretKey auditKey() {
        return new SecretKeySpec(Base64.getDecoder().decode(data().auditKeyB64), "AES");
    }

    /** Every token this account holds, in preference order. */
    public java.util.List<CredentialRecord> tokensFor(String account) {
        if (account == null) return java.util.List.of();
        return data().credentials().stream()
                .filter(c -> c.account.equalsIgnoreCase(account))
                .sorted(java.util.Comparator.comparingInt(c -> c.order))
                .toList();
    }

    /** Distinct accounts, since one account now spans several token records. */
    public java.util.List<String> accountNames() {
        return data().credentials().stream().map(c -> c.account).distinct().sorted().toList();
    }

    public Optional<CredentialRecord> find(String account, String group) {
        return account == null ? Optional.empty() : data().credentials().stream()
                .filter(c -> c.account.equalsIgnoreCase(account) && c.group.equalsIgnoreCase(group))
                .findFirst();
    }

    public Optional<CredentialRecord> anyFor(String account) {
        return tokensFor(account).stream().findFirst();
    }

    public Optional<OrgRecord> org(String id) {
        return id == null ? Optional.empty() : data().orgs().stream()
                .filter(o -> o.id.equalsIgnoreCase(id))
                .findFirst();
    }

    /**
     * The org named outright, else the one whose domains claim this address, else the only one there is.
     *
     * <p>A domain is a hint and not an identity, which the real inventory proves: two different OAuth
     * clients both serve {@code uskfoundation.or.ke}. So when more than one org claims the domain this
     * returns empty and forces an explicit choice, rather than guessing and minting the token under the
     * wrong Cloud project — which would still succeed, and quietly consume the wrong project's
     * unverified-app user cap.
     */
    public Optional<OrgRecord> orgFor(String id, String email) {
        var named = org(id);
        if (named.isPresent()) return named;
        var byDomain = data().orgs().stream().filter(o -> o.covers(email)).toList();
        if (byDomain.size() == 1) return Optional.of(byDomain.getFirst());
        if (byDomain.size() > 1) return Optional.empty();
        return data().orgs().size() == 1 ? Optional.of(data().orgs().getFirst()) : Optional.empty();
    }

    /** Lets an org adopt an account's domain only while no other org already answers for it. */
    public void claimDomain(OrgRecord org, String email) {
        var at = email == null ? -1 : email.indexOf('@');
        if (at < 0) return;
        var domain = email.substring(at + 1).toLowerCase();
        var taken = data().orgs().stream().anyMatch(o -> !o.id.equalsIgnoreCase(org.id) && o.covers(email));
        if (taken) {
            Log.info("not claiming " + domain + " for org '" + org.id
                    + "': another org already answers for it, so this account needs --org");
            return;
        }
        org.learn(email);
    }

    /** Bound to the passphrase and never written anywhere, which is the whole point of the parameter. */
    private static byte[] entropy(char[] phrase, byte[] salt) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(new String(phrase).getBytes(StandardCharsets.UTF_8));
            md.update("uskoag-wallet-dpapi-entropy".getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException("could not derive DPAPI entropy", e);
        }
    }
}

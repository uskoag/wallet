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
        salt = Aes.salt();
        master = Aes.derive(phrase, salt);
        passphrase = phrase.clone();
        data = new KeyringData();
        data.auditKeyB64 = Base64.getEncoder().encodeToString(Aes.randomKey().getEncoded());
        save();
    }

    public synchronized void unlock(char[] phrase) throws IOException {
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
        if (!Arrays.equals(passphrase, current)) throw new IOException("current passphrase does not match");
        if (fresh == null || fresh.length < MIN_PASSPHRASE) {
            throw new IOException("the new passphrase must be at least " + MIN_PASSPHRASE + " characters");
        }

        var previous = passphrase;
        salt = Aes.salt();
        master = Aes.derive(fresh, salt);
        passphrase = fresh.clone();
        save();
        Arrays.fill(previous, '\0');
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

package uskoag.wallet.daemon;

import uskoag.wallet.wire.WalletPaths;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/**
 * One wallet per wallet home.
 *
 * <p>Two instances is not merely untidy. They publish to the same {@code wallet.json}, so whichever
 * started last wins and every client silently talks to it — while the first still holds the unlocked
 * keyring, still holds the ArcadeDB lock on the audit, and still shows a tray icon. You end up
 * approving requests in one window that another process is serving, and the audit of what actually
 * happened is split across two stores.
 *
 * <p>The lock is per home rather than per machine, deliberately: a sandbox pointed at a different
 * {@code UKAG_WALLET_HOME} is a separate wallet and is allowed to run beside the real one.
 *
 * <p>Held for the life of the process. The JVM releases it on exit however the process dies, including
 * a kill, so a crashed wallet never leaves a stale lock behind.
 */
public final class SingleInstance {

    private static FileChannel channel;
    private static FileLock lock;

    private SingleInstance() {
    }

    /** True if this process now owns this wallet home. False means another wallet already does. */
    public static synchronized boolean claim() {
        try {
            Files.createDirectories(WalletPaths.home());
            var file = WalletPaths.home().resolve("wallet.lock");
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                channel = null;
                return false;
            }
            channel.truncate(0);
            channel.write(java.nio.ByteBuffer.wrap(
                    String.valueOf(ProcessHandle.current().pid()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            channel.force(true);
            return true;
        } catch (IOException e) {
            // A lock we cannot take is not a reason to refuse to start on a filesystem that cannot lock.
            Log.warn("could not take the single-instance lock, continuing anyway - " + e);
            return true;
        }
    }

    /** Whoever holds it, for the message the loser prints. */
    public static String holder() {
        try {
            var file = WalletPaths.home().resolve("wallet.lock");
            return Files.exists(file) ? Files.readString(file).trim() : "unknown";
        } catch (IOException e) {
            return "unknown";
        }
    }

    public static synchronized void release() {
        try {
            if (lock != null) lock.release();
            if (channel != null) channel.close();
        } catch (IOException ignored) {
        }
        lock = null;
        channel = null;
    }
}

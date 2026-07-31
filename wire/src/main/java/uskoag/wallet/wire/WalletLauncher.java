package uskoag.wallet.wire;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Starts the wallet on demand, the way ssh-agent does, so nothing has to be managed by hand.
 *
 * <p>Waits for the handshake file to appear rather than guessing a startup delay, and gives up rather
 * than falling back to anything that would put a secret back into a log.
 */
public final class WalletLauncher {

    private WalletLauncher() {
    }

    public static Optional<WalletClient> ensureRunning() {
        var existing = WalletClient.ifRunning();
        return existing.isPresent() ? existing : start();
    }

    public static Optional<WalletClient> start() {
        try {
            var jar = walletJar();
            if (jar.isEmpty()) return Optional.empty();
            Files.createDirectories(WalletPaths.home());
            // DPAPI is reached through java.lang.foreign; without this the JVM prints a restricted-method
            // warning today and will refuse the call outright in a later release.
            var pb = new ProcessBuilder(javaLauncher(), "--enable-native-access=ALL-UNNAMED",
                    "-Dfile.encoding=UTF-8", "-jar", jar.get().toString());
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.appendTo(WalletPaths.logFile().toFile()));
            pb.start();

            for (var i = 0; i < 90; i++) {
                var c = WalletClient.ifRunning();
                if (c.isPresent()) return c;
                Thread.sleep(500);
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** {@code javaw} from this JVM's own home when present, so the wallet starts without a console. */
    public static String javaLauncher() {
        var bin = Path.of(System.getProperty("java.home"), "bin");
        var javaw = bin.resolve("javaw.exe");
        if (Files.isExecutable(javaw)) return javaw.toString();
        var java = bin.resolve("java");
        return Files.isExecutable(java) ? java.toString() : "java";
    }

    /**
     * An explicit override, then beside the calling jar, then the dev reactor path. Never bundled
     * inside a client jar: version skew between an embedded wallet and an installed one is exactly the
     * bug you do not want to be debugging under pressure.
     */
    public static Optional<Path> walletJar() {
        var env = System.getenv("UKAG_WALLET_JAR");
        if (env != null && Files.exists(Path.of(env))) return Optional.of(Path.of(env));

        var candidates = new ArrayList<Path>();
        try {
            var self = Path.of(WalletLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            var dir = self.getParent();
            if (dir != null) {
                candidates.add(dir.resolve("uskoag-wallet.jar"));
                candidates.add(dir.resolve("../../ui/target/uskoag-wallet.jar").normalize());
            }
        } catch (Exception ignored) {
        }
        candidates.addAll(List.of(
                Path.of("wallet/ui/target/uskoag-wallet.jar"),
                Path.of("../ui/target/uskoag-wallet.jar")));

        for (var p : candidates) if (Files.exists(p)) return Optional.of(p);
        return Optional.empty();
    }
}

package uskoag.wallet.daemon;

import uskoag.wallet.wire.WalletPaths;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Terse, one line per event, to stderr and to a file. Never logs a passphrase, a token, a client secret
 * or a document's contents — the audit is the place for anything a person would want to read back.
 */
public final class Log {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Log() {
    }

    public static void info(String msg) {
        write("INFO ", msg);
    }

    public static void warn(String msg) {
        write("WARN ", msg);
    }

    public static void error(String msg, Throwable t) {
        write("ERROR", msg + (t == null ? "" : " — " + t));
    }

    private static final PrintWriter CONSOLE = new PrintWriter(
            new java.io.OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);

    private static synchronized void write(String level, String msg) {
        var line = LocalDateTime.now().format(STAMP) + " " + level + " " + msg;
        CONSOLE.println(line);
        try {
            Files.createDirectories(WalletPaths.home());
            try (var w = new PrintWriter(Files.newBufferedWriter(WalletPaths.logFile(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                w.println(line);
            }
        } catch (Exception ignored) {
        }
    }
}

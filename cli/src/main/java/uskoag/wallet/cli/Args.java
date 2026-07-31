package uskoag.wallet.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Flags, positionals, and a hidden prompt. No secret ever arrives as a flag — that is the whole point. */
public final class Args {

    final Map<String, String> flags = new LinkedHashMap<>();
    final List<String> rest = new ArrayList<>();

    public Args(String[] argv) {
        for (var i = 0; i < argv.length; i++) {
            var a = argv[i];
            if (!a.startsWith("--")) {
                rest.add(a);
                continue;
            }
            var name = a.substring(2);
            var eq = name.indexOf('=');
            if (eq > 0) {
                flags.put(name.substring(0, eq), name.substring(eq + 1));
            } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                flags.put(name, argv[++i]);
            } else {
                flags.put(name, "true");
            }
        }
    }

    public String verb() {
        return rest.isEmpty() ? "help" : rest.getFirst();
    }

    public String at(int index) {
        return rest.size() > index ? rest.get(index) : null;
    }

    public String get(String name, String fallback) {
        var v = flags.get(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    public boolean has(String name) {
        return "true".equals(flags.get(name));
    }

    public int num(String name, int fallback) {
        try {
            return flags.containsKey(name) ? Integer.parseInt(flags.get(name)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public List<String> list(String name) {
        var v = flags.get(name);
        return v == null || v.isBlank() ? List.of() : List.of(v.split(","));
    }

    /**
     * Hidden console read, or one line of piped stdin when {@code --stdin} was given.
     *
     * <p>A pipe is the one automation-safe way to hand over a secret: unlike a flag it is not in argv,
     * so no other process can read it from {@code Win32_Process.CommandLine} and it never reaches
     * PSReadLine history or an agent transcript. Returns null otherwise, which callers treat as fatal.
     */
    public String secret(String prompt) {
        if (has("stdin")) {
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(System.in,
                    java.nio.charset.StandardCharsets.UTF_8))) {
                var line = r.readLine();
                return line == null || line.isBlank() ? null : line.trim();
            } catch (java.io.IOException e) {
                return null;
            }
        }
        return fromConsole(prompt);
    }

    public static String fromConsole(String prompt) {
        var console = System.console();
        if (console == null) return null;
        var typed = console.readPassword("%s: ", prompt);
        if (typed == null) return null;
        var s = new String(typed);
        java.util.Arrays.fill(typed, '\0');
        return s;
    }
}

package uskoag.wallet.daemon;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which ancestor of the calling process a run of work should be pinned to.
 *
 * <p>The walk that this replaces went to the outermost ancestor it could see, and failed in both
 * directions at once. Measured on this machine it reached {@code explorer.exe}, which makes one session
 * out of an entire Windows login — so a permission the dialog described as covering "this one command"
 * actually covered every process the user was running, for as long as it stood. And thirteen of the
 * standing rules in the live keyring carried a different top pid each, five of them the same spreadsheet
 * approved five times inside 130 seconds, because the same walk often stopped at a shell that died with
 * the command.
 *
 * <p><b>A declaration decides it, and the declaration is checked.</b> Normally nothing a client says
 * about itself may be acted on, for the reason {@link uskoag.wallet.wire.CallerInfo} records. This is
 * different in kind: the wallet knows the real calling process from the kernel, so it can walk up and
 * confirm that the declared pid genuinely is one of its ancestors. A process therefore cannot declare
 * itself into somebody else's tree — the most it can do is pick a point on its own real chain, and the
 * stop set below refuses the one point on that chain that would be worth abusing.
 *
 * <p>The name lists are a fallback for when nothing is declared, and only a fallback. They will be
 * incomplete; nothing about security rests on them being right, because a wrong guess makes a session
 * narrower or wider within one process tree while the tier ceilings, the operation budgets and the
 * passphrase all still apply.
 */
public final class Anchors {

    /** Where a run of work usually lives: an agent or an editor that outlives the commands it spawns. */
    private static final Set<String> AGENT = Set.of("claude.exe", "node.exe", "code.exe", "cursor.exe");

    /** Failing that, the window somebody is typing in. */
    private static final Set<String> TERMINAL = Set.of("windowsterminal.exe", "tabby.exe", "wt.exe",
            "alacritty.exe", "mintty.exe", "conemu64.exe", "powershell.exe", "pwsh.exe", "cmd.exe",
            "bash.exe", "sh.exe", "zsh.exe", "fish.exe");

    /** Never an anchor, whoever asks. A rule bound to the desktop shell is a rule bound to everything. */
    private static final Set<String> STOP = Set.of("explorer.exe", "services.exe", "wininit.exe",
            "winlogon.exe", "svchost.exe", "lsass.exe", "csrss.exe", "smss.exe", "system",
            "runtimebroker.exe", "taskhostw.exe", "userinit.exe", "dllhost.exe");

    private static final int MAX_DEPTH = 25;

    private Anchors() {
    }

    public static Anchor resolve(long walkFrom, long declaredSource) {
        var chain = chain(walkFrom);
        if (chain.isEmpty()) return Anchor.unknown();

        if (declaredSource > 0) {
            var at = indexOf(chain, declaredSource);
            if (at < 0) {
                return heuristic(chain).warn("this run declared pid " + declaredSource + " as its source"
                        + " process, and that pid is not an ancestor of the calling process. The"
                        + " declaration was discarded. If a daemon is forwarding somebody else's"
                        + " invocation this is expected; otherwise something is misreporting itself.");
            }
            if (STOP.contains(name(chain.get(at)))) {
                return heuristic(chain).warn("this run declared " + name(chain.get(at)) + " (pid "
                        + declaredSource + ") as its source process. That is a system process and cannot"
                        + " anchor a session, because a permission bound to it is bound to everything"
                        + " running as you. The declaration was discarded.");
            }
            return at(chain.get(at), "declared");
        }
        return heuristic(chain);
    }

    /**
     * Outermost agent, else outermost terminal, else the outermost thing that is not a system process.
     *
     * <p>Outermost rather than nearest at every tier, because the nearest shell is usually the one spawned
     * for this single command and dies with it — which is the fragmentation this exists to end.
     */
    private static Anchor heuristic(List<ProcessHandle> chain) {
        var found = outermost(chain, AGENT);
        if (found != null) return at(found, "the agent that started this run");
        found = outermost(chain, TERMINAL);
        if (found != null) return at(found, "the terminal this ran in");
        for (var i = chain.size() - 1; i >= 0; i--) {
            if (!STOP.contains(name(chain.get(i)))) return at(chain.get(i), "outermost non-system ancestor");
        }
        return at(chain.get(0), "the calling process itself");
    }

    private static ProcessHandle outermost(List<ProcessHandle> chain, Set<String> wanted) {
        for (var i = chain.size() - 1; i >= 0; i--) {
            var p = chain.get(i);
            if (wanted.contains(name(p)) && !STOP.contains(name(p))) return p;
        }
        return null;
    }

    private static Anchor at(ProcessHandle p, String how) {
        return new Anchor(p.pid(), p.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L),
                name(p), how, null);
    }

    /** The calling process first, then its ancestors, for as far as this process is allowed to see. */
    public static List<ProcessHandle> chain(long pid) {
        var out = new ArrayList<ProcessHandle>();
        try {
            var p = pid > 0 ? ProcessHandle.of(pid).orElse(null) : null;
            while (p != null && out.size() < MAX_DEPTH) {
                out.add(p);
                p = p.parent().orElse(null);
            }
        } catch (Exception e) {
            Log.warn("could not walk the process chain from pid " + pid + ": " + e);
        }
        return out;
    }

    public static String describe(List<ProcessHandle> chain) {
        var parts = new ArrayList<String>();
        for (var p : chain) parts.add(p.pid() + ":" + name(p));
        return String.join(" <- ", parts);
    }

    private static int indexOf(List<ProcessHandle> chain, long pid) {
        for (var i = 0; i < chain.size(); i++) if (chain.get(i).pid() == pid) return i;
        return -1;
    }

    static String name(ProcessHandle p) {
        return p.info().command()
                .map(c -> c.substring(c.replace('\\', '/').lastIndexOf('/') + 1).toLowerCase(Locale.ROOT))
                .orElse("?");
    }
}

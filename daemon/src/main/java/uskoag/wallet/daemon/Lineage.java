package uskoag.wallet.daemon;

import leasegate.core.access.AnchorCategory;
import leasegate.core.access.AnchorSuggestion;
import leasegate.core.access.CategoryClassifier;
import leasegate.core.access.ProcessTree;
import uskoag.wallet.wire.AncestryHop;
import uskoag.wallet.wire.Tier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The calling process and every ancestor of it, read once when a grant is issued.
 *
 * <p>Read then, not when a window opens, because the chain is the part that cannot be recovered later: a
 * wrapper shell that exists only to start the next stage has often exited a few milliseconds on, and a dead
 * process's parent can no longer be asked by anyone. The walk also continues through the MSYS process
 * table where a Git Bash fork leaves dead Windows parents behind.
 *
 * <p>What matches a rule is {@link #ids()}: a rule anchored at a process applies to a request whose lineage
 * contains that process. What a person sees is {@link #hops}, which adds how many processes each ancestor
 * currently covers and whether it may be picked at all.
 */
public record Lineage(List<ProcessTree.RawLink> links, List<String> ids) {

    public static final Lineage NONE = new Lineage(List.of(), List.of());

    /** A lone shell can be picked, but it is short-lived and wide, so it is held to half an hour. */
    static final int SHELL_CAP_MINUTES = 30;

    public static Lineage of(long pid) {
        if (pid <= 0) return NONE;
        try {
            var links = ProcessTree.rawChain(pid);
            return new Lineage(List.copyOf(links), links.stream().map(Lineage::id).toList());
        } catch (Exception e) {
            Log.warn("could not walk the process chain from pid " + pid + ": " + e);
            return NONE;
        }
    }

    static String id(ProcessTree.RawLink l) {
        return "proc-" + l.pid() + "-" + l.startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
    }

    /**
     * The chain as the approval window shows it, each hop with the longest approval it may carry for this
     * tier. The desktop shell, a terminal hosting several tabs, and the wallet's own system-process stop
     * list get 0 and cannot be picked: an approval anchored there covers everything this user runs.
     */
    public List<AncestryHop> hops(Tier tier) {
        if (links.isEmpty()) return List.of();
        var tree = ProcessTree.snapshot();
        var nodes = tree.decorate(links);
        var ceiling = tier.ceiling(true);
        var out = new ArrayList<AncestryHop>();
        for (var i = 0; i < nodes.size(); i++) {
            var n = nodes.get(i);
            var category = CategoryClassifier.classify(n, i, nodes.size(), tree);
            var cap = switch (category) {
                case SESSION_ROOT, TERMINAL_HOST -> 0;
                case SHELL_OR_TAB -> Math.min(SHELL_CAP_MINUTES, ceiling);
                case AGENT_SESSION, ORDINARY_PROCESS -> ceiling;
            };
            if (Anchors.STOP.contains(n.imageName())) cap = 0;
            out.add(new AncestryHop(i, n.pid(), n.startInstant().map(java.time.Instant::toEpochMilli).orElse(0L),
                    n.imageName(), n.descendantCount(), words(category), cap));
        }
        return out;
    }

    /**
     * Which hop the window selects first: the agent when there is one, since an agent outlives every command
     * it runs, otherwise the library's own suggestion (the shallowest ancestor clearly longer-lived than the
     * caller), otherwise nothing and the person picks.
     */
    public int suggested(List<AncestryHop> hops) {
        for (var h : hops) if (h.selectable() && AGENTS.contains(h.image())) return h.hop();
        if (links.isEmpty()) return -1;
        var tree = ProcessTree.snapshot();
        var at = AnchorSuggestion.suggest(tree.decorate(links), tree);
        return at >= 0 && at < hops.size() && hops.get(at).selectable() ? at : -1;
    }

    private static final Set<String> AGENTS = Set.of("claude.exe", "codex.exe", "cursor.exe", "code.exe");

    private static String words(AnchorCategory c) {
        return switch (c) {
            case SESSION_ROOT -> "desktop";
            case TERMINAL_HOST -> "terminal";
            case SHELL_OR_TAB -> "shell";
            case AGENT_SESSION -> "agent";
            case ORDINARY_PROCESS -> "program";
        };
    }
}

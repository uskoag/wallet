package uskoag.wallet.daemon;

import leasegate.core.access.ProcessTree;
import uskoag.wallet.wire.AncestryHop;

import java.util.ArrayList;
import java.util.List;

/**
 * The calling process and every ancestor of it, read once when a grant is issued.
 *
 * <p>Read then, not when a window opens, because the chain is the part that cannot be recovered later: a
 * wrapper shell that exists only to start the next stage has often exited a few milliseconds on, and a dead
 * process's parent can no longer be asked by anyone. The walk also continues through the MSYS process
 * table where a Git Bash fork leaves dead Windows parents behind.
 *
 * <p>What matches a rule is {@link #ids()}: a rule anchored at a process applies to a request whose lineage
 * contains that process. What the approval window shows is {@link #hops()}.
 */
public record Lineage(List<ProcessTree.RawLink> links, List<String> ids) {

    public static final Lineage NONE = new Lineage(List.of(), List.of());

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
        return AncestryHop.id(l.pid(), l.startInstant().map(java.time.Instant::toEpochMilli).orElse(0L));
    }

    public List<AncestryHop> hops() {
        var out = new ArrayList<AncestryHop>();
        for (var i = 0; i < links.size(); i++) {
            var l = links.get(i);
            out.add(new AncestryHop(i, l.pid(), l.startInstant().map(java.time.Instant::toEpochMilli).orElse(0L),
                    l.imagePath()));
        }
        return out;
    }
}

package uskoag.wallet.daemon;

/**
 * What the wallet would pin a run of work to, from this process. Prints the chain it walked and the
 * anchor it chose, then checks the declaration rules: an ancestor is honoured, a stranger is refused,
 * and the desktop shell is refused however it is asked for.
 */
public final class AnchorProbe {

    private static int failures;

    public static void main(String[] args) {
        var me = ProcessHandle.current().pid();
        var chain = Anchors.chain(me);
        System.out.println("chain : " + Anchors.describe(chain));

        var chosen = Anchors.resolve(me, 0);
        System.out.println("anchor: " + chosen.describe() + "   (" + chosen.how() + ")");
        System.out.println("id    : " + chosen.id());
        check("an anchor was found", chosen.known());
        check("the desktop shell is never the anchor", !"explorer.exe".equals(chosen.name()));
        check("the anchor is a real ancestor",
                chain.stream().anyMatch(p -> p.pid() == chosen.pid()));

        if (chain.size() > 1) {
            var parent = chain.get(1);
            var declared = Anchors.resolve(me, parent.pid());
            check("a declared ancestor is honoured",
                    declared.pid() == parent.pid() && "declared".equals(declared.how()));
            check("honouring it says nothing alarming", declared.warning() == null);
        }

        var stranger = Anchors.resolve(me, 999_999_999L);
        check("a declared stranger is discarded", stranger.pid() == chosen.pid());
        check("and is complained about", stranger.warning() != null
                && stranger.warning().contains("not an ancestor"));

        var shell = chain.stream().filter(p -> "explorer.exe".equals(Anchors.name(p))).findFirst();
        if (shell.isPresent()) {
            var asked = Anchors.resolve(me, shell.get().pid());
            check("declaring the desktop shell is refused", asked.pid() != shell.get().pid());
            check("and is complained about", asked.warning() != null);
        } else {
            System.out.println("  --   explorer.exe is not in this chain, so that case is untested here");
        }

        check("an unidentifiable caller yields no anchor", !Anchors.resolve(0, 0).known());

        System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String what, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }

    private AnchorProbe() {
    }
}

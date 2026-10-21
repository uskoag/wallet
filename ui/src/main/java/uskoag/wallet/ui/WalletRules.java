package uskoag.wallet.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import leasegate.core.access.DurationCaps;
import leasegate.core.access.LeaseRules;
import leasegate.core.access.ScopeLine;
import uskoag.wallet.daemon.WalletSettings;
import uskoag.wallet.wire.ResourceRef;
import uskoag.wallet.wire.Tier;

import java.util.ArrayList;
import java.util.List;

/**
 * The wallet's own part of an approval: how a request reads, how long its tier may stand, and when the
 * passphrase is needed. The anchor, its breadth and the structural caps are the shared approval model's.
 */
public final class WalletRules implements LeaseRules<WalletScope> {

    private final WalletSettings settings;

    public WalletRules(WalletSettings settings) {
        this.settings = settings;
    }

    @Override
    public String product() {
        return uskoag.wallet.wire.Brand.NAME;
    }

    /** The tier's ceiling for a rule covering every document: a day to read, four hours to change, an hour to do anything irreversible. */
    @Override
    public DurationCaps.Result capByScope(DurationCaps.Result base, WalletScope s) {
        var ceiling = s.tier().ceiling(true);
        return ceiling < base.minutes() ? new DurationCaps.Result(ceiling, "the " + word(s.tier()) + " ceiling") : base;
    }

    @Override
    public int keyedMaxMinutes(WalletScope s) {
        return 0;
    }

    @Override
    public int riskBase(WalletScope s) {
        return switch (s.tier()) {
            case READ -> 0;
            case MUTATE -> 1;
            case DESTRUCTIVE -> 2;
        };
    }

    /** Irreversible requests need the passphrase, whatever the anchor; reading and changing are bounded by the anchor. */
    @Override
    public boolean needsPassphrase(WalletScope s) {
        return s.tier() == Tier.DESTRUCTIVE && settings.destructiveNeedsPassphrase;
    }

    @Override
    public String passphraseReason(WalletScope s) {
        return "This cannot be undone.";
    }

    @Override
    public List<ScopeLine> describe(WalletScope s) {
        var lines = new ArrayList<ScopeLine>();
        var what = s.itemCount() > 1 ? s.operation() + " (" + s.itemCount() + " items)" : s.operation();
        lines.add(ScopeLine.note(what + " on " + document(s.resource()) + "  ·  " + text(s.resourceKind())));
        if (s.resource() != null && s.resource().id() != null) lines.add(ScopeLine.note("id " + s.resource().id()));
        lines.add(summary(s));
        return lines;
    }

    @Override
    public ScopeLine summary(WalletScope s) {
        var text = "If approved: whatever the chosen process starts may " + verbs(s.tier()) + " any document on "
                + text(s.account()) + (s.ops() >= 0 ? ", up to " + s.ops() + " operations" : "") + ".";
        return switch (s.tier()) {
            case READ -> ScopeLine.narrow(text);
            case MUTATE -> ScopeLine.caution(text);
            case DESTRUCTIVE -> ScopeLine.wide(text);
        };
    }

    @Override
    public void writeScope(ObjectNode into, WalletScope s) {
        into.put("account", s.account());
        into.put("tier", s.tier().name());
        into.put("ops", s.ops());
    }

    @Override
    public WalletScope readScope(JsonNode from) {
        return new WalletScope(from.path("account").asText(null), Tier.valueOf(from.path("tier").asText("READ")),
                null, 1, null, null, from.path("ops").asInt(-1));
    }

    @Override
    public WalletScope stored(WalletScope s) {
        return s;
    }

    static String verbs(Tier tier) {
        return switch (tier) {
            case DESTRUCTIVE -> "read, change, delete, move or share";
            case MUTATE -> "read and change";
            case READ -> "read";
        };
    }

    static String word(Tier tier) {
        return switch (tier) {
            case DESTRUCTIVE -> "irreversible";
            case MUTATE -> "change";
            case READ -> "read";
        };
    }

    static String document(ResourceRef r) {
        if (r == null || r.id() == null) return "every document";
        return r.label() == null || r.label().isBlank() ? "an unidentified resource" : r.label();
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }
}

package dev.marshalhq.core.whitelist;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A single loaded whitelist document: its {@code source}, schema {@code version},
 * {@code updated} timestamp (used for source selection; null for the user list which
 * is never timestamp-selected), and its entries indexed by GAV for O(1) lookup.
 */
public final class Whitelist {

    private final WhitelistSource source;
    private final int version;
    private final Instant updated;
    private final Map<String, WhitelistEntry> byGav;

    public Whitelist(WhitelistSource source, int version, Instant updated, List<WhitelistEntry> entries) {
        this.source = source;
        this.version = version;
        this.updated = updated;
        Map<String, WhitelistEntry> map = new LinkedHashMap<>();
        for (WhitelistEntry e : entries) {
            map.put(e.gav(), e);
        }
        this.byGav = map;
    }

    public static Whitelist empty(WhitelistSource source) {
        return new Whitelist(source, 1, null, List.of());
    }

    /** The matching, non-expired entry for an exact GAV, if any. */
    public Optional<WhitelistEntry> find(String gav, LocalDate asOf) {
        WhitelistEntry entry = byGav.get(gav);
        if (entry == null || entry.isExpired(asOf)) {
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    public WhitelistSource source() {
        return source;
    }

    public int version() {
        return version;
    }

    public Instant updated() {
        return updated;
    }

    public List<WhitelistEntry> entries() {
        return List.copyOf(byGav.values());
    }
}

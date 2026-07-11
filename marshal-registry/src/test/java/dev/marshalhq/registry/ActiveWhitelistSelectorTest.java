package dev.marshalhq.registry;

import dev.marshalhq.core.whitelist.Whitelist;
import dev.marshalhq.core.whitelist.WhitelistSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source selection goes by timestamp, not fixed priority: the trusted source with the
 * newest {@code updated} wins. A jar upgrade can ship an embedded baseline newer than a
 * stale cache, so a blind "cache before embedded" order would serve stale data.
 * Newest-timestamp-wins gets it right in every case.
 */
class ActiveWhitelistSelectorTest {

    private static Whitelist at(String updated) {
        return new Whitelist(WhitelistSource.MARSHAL, 1,
                updated == null ? null : Instant.parse(updated), List.of());
    }

    @Test
    void freshRemoteWinsWhenNewest() {
        Whitelist fresh = at("2026-06-23T00:00:00Z");
        Whitelist cached = at("2026-06-20T00:00:00Z");
        Whitelist embedded = at("2026-06-01T00:00:00Z");

        assertThat(ActiveWhitelistSelector.newest(embedded, cached, fresh).updated())
                .isEqualTo(Instant.parse("2026-06-23T00:00:00Z"));
    }

    @Test
    void newerEmbeddedBaselineBeatsStaleCache() {
        // The jar-upgrade case the spec calls out explicitly.
        Whitelist cached = at("2026-05-01T00:00:00Z");
        Whitelist embedded = at("2026-06-20T00:00:00Z");

        assertThat(ActiveWhitelistSelector.newest(embedded, cached, null).updated())
                .isEqualTo(Instant.parse("2026-06-20T00:00:00Z"));
    }

    @Test
    void cachedRemoteWinsWhenNewerThanEmbeddedAndNoFresh() {
        Whitelist cached = at("2026-06-21T00:00:00Z");
        Whitelist embedded = at("2026-06-01T00:00:00Z");

        assertThat(ActiveWhitelistSelector.newest(embedded, cached, null).updated())
                .isEqualTo(Instant.parse("2026-06-21T00:00:00Z"));
    }

    @Test
    void nullCandidatesAreIgnored() {
        Whitelist embedded = at("2026-06-01T00:00:00Z");
        assertThat(ActiveWhitelistSelector.newest(embedded, null, null).updated())
                .isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void onExactTieTheEmbeddedFloorIsPreferred() {
        // Defends against a validly-signed-but-equal-timestamp rollback: the embedded
        // baseline is passed first and wins ties.
        Whitelist embedded = at("2026-06-20T00:00:00Z");
        Whitelist cached = at("2026-06-20T00:00:00Z");

        assertThat(ActiveWhitelistSelector.newest(embedded, cached, null).source())
                .isEqualTo(WhitelistSource.MARSHAL);
        assertThat(ActiveWhitelistSelector.newest(embedded, cached, null)).isSameAs(embedded);
    }

    @Test
    void treatsMissingTimestampAsOldest() {
        Whitelist noTs = at(null);
        Whitelist withTs = at("2026-06-01T00:00:00Z");
        assertThat(ActiveWhitelistSelector.newest(withTs, noTs).updated())
                .isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    }
}

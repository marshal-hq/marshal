package dev.marshalhq.core.whitelist;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WhitelistTest {

    private static WhitelistEntry entry(String gav, LocalDate expires) {
        return new WhitelistEntry(gav, "vetted", "usman", LocalDate.parse("2026-06-22"), expires);
    }

    @Test
    void matchesOnFullGavOnly() {
        Whitelist wl = new Whitelist(WhitelistSource.USER, 1, null,
                List.of(entry("com.acme:lib:1.2.0", null)));

        assertThat(wl.find("com.acme:lib:1.2.0", LocalDate.parse("2026-06-22"))).isPresent();
        // Different version is NOT covered — pinning means a bump leaves the whitelist.
        assertThat(wl.find("com.acme:lib:1.3.0", LocalDate.parse("2026-06-22"))).isEmpty();
        // groupId:artifactId without version is never a match.
        assertThat(wl.find("com.acme:lib", LocalDate.parse("2026-06-22"))).isEmpty();
    }

    @Test
    void expiredEntryStopsSuppressing() {
        Whitelist wl = new Whitelist(WhitelistSource.USER, 1, null,
                List.of(entry("com.acme:lib:1.2.0", LocalDate.parse("2026-12-22"))));

        // Before and on the expiry date: still suppresses.
        assertThat(wl.find("com.acme:lib:1.2.0", LocalDate.parse("2026-12-01"))).isPresent();
        assertThat(wl.find("com.acme:lib:1.2.0", LocalDate.parse("2026-12-22"))).isPresent();
        // After the expiry date: re-evaluated by the engine, no suppression.
        assertThat(wl.find("com.acme:lib:1.2.0", LocalDate.parse("2026-12-23"))).isEmpty();
    }

    @Test
    void entriesWithoutExpiryNeverLapse() {
        // Marshal-maintained entries carry no expires; they suppress indefinitely.
        Whitelist wl = new Whitelist(WhitelistSource.MARSHAL, 1, Instant.parse("2026-06-22T00:00:00Z"),
                List.of(new WhitelistEntry("org.springframework:spring-core:6.1.5", "top-200", null, null, null)));

        assertThat(wl.find("org.springframework:spring-core:6.1.5", LocalDate.parse("2099-01-01"))).isPresent();
    }

    @Test
    void exposesUpdatedTimestampForSourceSelection() {
        Instant updated = Instant.parse("2026-06-22T00:00:00Z");
        Whitelist wl = new Whitelist(WhitelistSource.MARSHAL, 1, updated, List.of());
        assertThat(wl.updated()).isEqualTo(updated);
        assertThat(wl.source()).isEqualTo(WhitelistSource.MARSHAL);
    }
}

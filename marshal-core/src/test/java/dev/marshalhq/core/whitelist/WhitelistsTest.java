package dev.marshalhq.core.whitelist;

import dev.marshalhq.core.SuppressionInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Precedence is fixed: the Marshal-maintained list is checked first, then the user
 * list. A GAV present in either is suppressed, and we record which list matched for
 * the audit trail.
 */
class WhitelistsTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-06-22");

    private static Whitelist marshal(WhitelistEntry... entries) {
        return new Whitelist(WhitelistSource.MARSHAL, 1, Instant.parse("2026-06-22T00:00:00Z"), List.of(entries));
    }

    private static Whitelist user(WhitelistEntry... entries) {
        return new Whitelist(WhitelistSource.USER, 1, null, List.of(entries));
    }

    @Test
    void suppressesWhenPresentInMarshalList() {
        Whitelists wls = new Whitelists(
                marshal(new WhitelistEntry("org.springframework:spring-core:6.1.5", "top-200", null, null, null)),
                user());

        Optional<SuppressionInfo> info = wls.decide("org.springframework:spring-core:6.1.5", TODAY);
        assertThat(info).isPresent();
        assertThat(info.get().matchedList()).isEqualTo("marshal");
        assertThat(info.get().reason()).isEqualTo("top-200");
    }

    @Test
    void suppressesWhenPresentInUserList() {
        Whitelists wls = new Whitelists(
                marshal(),
                user(new WhitelistEntry("com.acme:lib:1.2.0", "internal", "usman",
                        LocalDate.parse("2026-06-22"), LocalDate.parse("2026-12-22"))));

        Optional<SuppressionInfo> info = wls.decide("com.acme:lib:1.2.0", TODAY);
        assertThat(info).isPresent();
        assertThat(info.get().matchedList()).isEqualTo("user");
        assertThat(info.get().expires()).isEqualTo("2026-12-22");
        assertThat(info.get().addedBy()).isEqualTo("usman");
    }

    @Test
    void marshalListWinsWhenPresentInBoth() {
        Whitelists wls = new Whitelists(
                marshal(new WhitelistEntry("com.acme:lib:1.2.0", "from-marshal", null, null, null)),
                user(new WhitelistEntry("com.acme:lib:1.2.0", "from-user", "usman",
                        LocalDate.parse("2026-06-22"), LocalDate.parse("2026-12-22"))));

        SuppressionInfo info = wls.decide("com.acme:lib:1.2.0", TODAY).orElseThrow();
        assertThat(info.matchedList()).isEqualTo("marshal");
        assertThat(info.reason()).isEqualTo("from-marshal");
    }

    @Test
    void noMatchInEitherListIsNotSuppressed() {
        Whitelists wls = new Whitelists(marshal(), user());
        assertThat(wls.decide("com.acme:unknown:9.9.9", TODAY)).isEmpty();
    }

    @Test
    void expiredUserEntryFallsThroughToReporting() {
        Whitelists wls = new Whitelists(
                marshal(),
                user(new WhitelistEntry("com.acme:lib:1.2.0", "internal", "usman",
                        LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"))));

        assertThat(wls.decide("com.acme:lib:1.2.0", TODAY)).isEmpty();
    }

    @Test
    void emptyFactoryProducesNoSuppression() {
        Whitelists wls = Whitelists.empty();
        assertThat(wls.decide("com.acme:lib:1.2.0", TODAY)).isEmpty();
    }
}

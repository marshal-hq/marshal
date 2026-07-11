package dev.marshalhq.cli;

import dev.marshalhq.core.Coordinates;
import dev.marshalhq.core.Finding;
import dev.marshalhq.core.Severity;
import dev.marshalhq.core.whitelist.Whitelist;
import dev.marshalhq.core.whitelist.WhitelistEntry;
import dev.marshalhq.core.whitelist.WhitelistSource;
import dev.marshalhq.core.whitelist.Whitelists;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The suppression pass {@link CliHelper#applySuppression} mutates a finding into its
 * suppressed form only when (a) its GAV is whitelisted and (b) the engine actually
 * raised it (RED/ORANGE/YELLOW). GREEN and unresolved findings are never suppressed.
 */
class WhitelistSuppressionTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-06-22");

    private static Finding finding(String gav, Severity level) {
        String[] p = gav.split(":");
        Coordinates c = new Coordinates(p[0], p[1], p[2]);
        return new Finding(c, "0.9.0", p[2], level == Severity.GREEN ? 5 : 80, level, List.of(), false, false);
    }

    private static Whitelists withUserEntry(String gav) {
        Whitelist user = new Whitelist(WhitelistSource.USER, 1, null,
                List.of(new WhitelistEntry(gav, "vetted", "usman",
                        LocalDate.parse("2026-06-01"), LocalDate.parse("2026-12-22"))));
        return new Whitelists(Whitelist.empty(WhitelistSource.MARSHAL), user);
    }

    @Test
    void flaggedFindingWithWhitelistedGavBecomesSuppressed() {
        Finding red = finding("com.acme:lib:1.2.0", Severity.RED);
        List<Finding> out = CliHelper.applySuppression(List.of(red), withUserEntry("com.acme:lib:1.2.0"), TODAY);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).suppressed()).isTrue();
        assertThat(out.get(0).suppression().matchedList()).isEqualTo("user");
    }

    @Test
    void differentVersionIsNotSuppressed() {
        Finding red = finding("com.acme:lib:1.3.0", Severity.RED);
        List<Finding> out = CliHelper.applySuppression(List.of(red), withUserEntry("com.acme:lib:1.2.0"), TODAY);
        assertThat(out.get(0).suppressed()).isFalse();
    }

    @Test
    void greenFindingIsNeverSuppressedEvenWhenWhitelisted() {
        Finding green = finding("com.acme:lib:1.2.0", Severity.GREEN);
        List<Finding> out = CliHelper.applySuppression(List.of(green), withUserEntry("com.acme:lib:1.2.0"), TODAY);
        assertThat(out.get(0).suppressed()).isFalse();
    }

    @Test
    void unresolvedFindingIsNeverSuppressed() {
        Finding unres = Finding.unresolved(new Coordinates("com.acme", "lib", "1.2.0"));
        List<Finding> out = CliHelper.applySuppression(List.of(unres), withUserEntry("com.acme:lib:1.2.0"), TODAY);
        assertThat(out.get(0).suppressed()).isFalse();
    }

    @Test
    void emptyWhitelistsLeaveFindingsUntouched() {
        Finding red = finding("com.acme:lib:1.2.0", Severity.RED);
        List<Finding> out = CliHelper.applySuppression(List.of(red), Whitelists.empty(), TODAY);
        assertThat(out.get(0).suppressed()).isFalse();
    }
}

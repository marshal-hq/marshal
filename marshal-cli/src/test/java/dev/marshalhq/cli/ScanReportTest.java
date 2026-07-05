package dev.marshalhq.cli;

import dev.marshalhq.core.Coordinates;
import dev.marshalhq.core.Finding;
import dev.marshalhq.core.Severity;
import dev.marshalhq.core.SuppressionInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A suppressed finding is excluded from every risk bucket and from the worst-severity
 * used to gate the exit code, but it is retained in {@code all()} (for JSON audit) and
 * counted in a dedicated {@code suppressed} bucket.
 */
class ScanReportTest {

    private static Finding finding(String ga, Severity level, int score) {
        String[] p = ga.split(":");
        return new Finding(new Coordinates(p[0], p[1], "1.0.0"), null, "1.0.0", score, level,
                List.of(), false, false);
    }

    private static Finding suppressed(String ga, Severity level, int score) {
        return finding(ga, level, score)
                .withSuppression(new SuppressionInfo("marshal", "vetted", null, null));
    }

    @Test
    void suppressedRedIsRemovedFromFlaggedAndWorstSeverity() {
        ScanReport report = ScanReport.from(List.of(
                suppressed("com.acme:flagged-but-trusted", Severity.RED, 90)));

        assertThat(report.flagged()).isEmpty();
        assertThat(report.flaggedCount()).isZero();
        assertThat(report.suppressedCount()).isEqualTo(1);
        assertThat(report.worstSeverity()).isEmpty();      // suppressed RED must not gate CI
        assertThat(report.count(Severity.RED)).isZero();   // excluded from risk distribution
        assertThat(report.all()).hasSize(1);               // retained for the audit record
    }

    @Test
    void suppressedYellowIsRemovedFromAdvisory() {
        ScanReport report = ScanReport.from(List.of(
                suppressed("com.acme:advisory-trusted", Severity.YELLOW, 30),
                finding("com.acme:advisory", Severity.YELLOW, 40)));

        assertThat(report.advisoryCount()).isEqualTo(1);
        assertThat(report.suppressedCount()).isEqualTo(1);
        assertThat(report.advisory()).extracting(f -> f.coordinates().toGa())
                .containsExactly("com.acme:advisory");
    }

    @Test
    void unsuppressedFlaggedStillGatesExit() {
        ScanReport report = ScanReport.from(List.of(
                suppressed("com.acme:trusted", Severity.RED, 90),
                finding("com.acme:real-risk", Severity.ORANGE, 60)));

        assertThat(report.worstSeverity()).contains(Severity.ORANGE);
        assertThat(report.flaggedCount()).isEqualTo(1);
        assertThat(report.suppressedCount()).isEqualTo(1);
    }

    @Test
    void suppressedBucketIsEmptyWhenNothingSuppressed() {
        ScanReport report = ScanReport.from(List.of(
                finding("com.acme:a", Severity.RED, 90)));
        assertThat(report.suppressedCount()).isZero();
        assertThat(report.suppressed()).isEmpty();
    }
}

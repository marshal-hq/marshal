package dev.marshalhq.cli;

import dev.marshalhq.core.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownReporterTest {

    private final MarkdownReporter reporter = new MarkdownReporter();

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private static Finding finding(String ga, String from, String to,
                                   int score, Severity level, List<RuleResult> signals) {
        String[] p = ga.split(":");
        return new Finding(new Coordinates(p[0], p[1], to),
            from, to, score, level, signals, false, false);
    }

    private static RuleResult signal(String ruleId, int pts, String evidence) {
        return new RuleResult(pts, Severity.ORANGE, evidence, ruleId);
    }

    // ── idempotency marker ────────────────────────────────────────────────────────

    @Test
    void firstLineIsIdempotencyMarker() {
        String md = reporter.render(List.of());
        assertThat(md.lines().findFirst().orElse("")).isEqualTo("<!-- marshal-bot -->");
    }

    // ── header ────────────────────────────────────────────────────────────────────

    @Test
    void headerLine() {
        String md = reporter.render(List.of());
        assertThat(md).contains("## 🛡 Marshal Dependency Analysis");
    }

    // ── summary line ──────────────────────────────────────────────────────────────

    @Test
    void summaryLine_noFindings() {
        String md = reporter.render(List.of());
        assertThat(md).contains("**0 updates — 0 flagged**");
    }

    @Test
    void summaryLine_flaggedCount_isRedPlusOrange() {
        Finding red    = finding("a:b", "1.0", "2.0", 87, Severity.RED,
            List.of(signal("SIG-DROPPED", 40, "sig dropped")));
        Finding orange = finding("c:d", "2.0", "3.0", 62, Severity.ORANGE,
            List.of(signal("REPO-CHANGED", 20, "url changed")));
        Finding green  = finding("e:f", null, "1.0", 4, Severity.GREEN, List.of());

        String md = reporter.render(List.of(red, orange, green));
        // visible = red + orange + green = 3, flagged = 2, no advisory
        assertThat(md).contains("**3 updates — 2 flagged**");
    }

    @Test
    void summaryLine_yellowAdvisoryClause_appearsWhenYellowPresent() {
        Finding yellow = finding("a:b", "1.0", "2.0", 34, Severity.YELLOW, List.of());
        Finding green  = finding("c:d", null, "1.0", 4, Severity.GREEN, List.of());

        String md = reporter.render(List.of(yellow, green));
        // yellow excluded from output (not counted in visible N), but shows in advisory
        assertThat(md).contains("advisory");
        assertThat(md).contains("1 advisory");
    }

    @Test
    void summaryLine_noAdvisoryClause_whenNoYellow() {
        Finding red = finding("a:b", "1.0", "2.0", 87, Severity.RED,
            List.of(signal("SIG-DROPPED", 40, "ev")));
        String md = reporter.render(List.of(red));
        assertThat(md).doesNotContain("advisory");
    }

    // ── RED finding ───────────────────────────────────────────────────────────────

    @Test
    void redFinding_headingContainsHighRiskEmoji() {
        Finding red = finding("com.example:some-lib", "1.2.3", "2.0.0", 87, Severity.RED,
            List.of(signal("NEW-MAINTAINER", 35, "Publisher changed")));
        String md = reporter.render(List.of(red));
        assertThat(md).contains("### 🔴 HIGH RISK — com.example:some-lib `1.2.3 → 2.0.0`");
    }

    @Test
    void redFinding_riskScoreLine() {
        Finding red = finding("a:b", "1.0", "2.0", 87, Severity.RED,
            List.of(signal("SIG-DROPPED", 40, "ev")));
        assertThat(reporter.render(List.of(red))).contains("**Risk score: 87/100**");
    }

    @Test
    void redFinding_multiSignalTable() {
        Finding red = finding("a:b", "1.0", "2.0", 87, Severity.RED, List.of(
            signal("NEW-MAINTAINER", 35, "Publisher changed from alice to bob"),
            signal("SIG-DROPPED",    40, "Prior 3 releases were signed")
        ));
        String md = reporter.render(List.of(red));

        assertThat(md).contains("| Signal | Detail |");
        assertThat(md).contains("|--------|--------|");
        assertThat(md).contains("| Maintainer changed | Publisher changed from alice to bob |");
        assertThat(md).contains("| Signature dropped | Prior 3 releases were signed |");
    }

    @Test
    void redFinding_doNotMergeRecommendation() {
        Finding red = finding("a:b", "1.0", "2.0", 87, Severity.RED,
            List.of(signal("SIG-DROPPED", 40, "ev")));
        assertThat(reporter.render(List.of(red))).contains("**Recommendation:** Do not merge.");
    }

    @Test
    void redFinding_firstSeen_noArrowInHeading() {
        Finding red = finding("a:b", null, "1.0.0", 87, Severity.RED,
            List.of(signal("MISSING-SIG", 15, "no sig")));
        String md = reporter.render(List.of(red));
        // Heading backtick span should just be the to-version
        assertThat(md).contains("### 🔴 HIGH RISK — a:b `1.0.0`");
    }

    // ── ORANGE finding ────────────────────────────────────────────────────────────

    @Test
    void orangeFinding_headingContainsModerateRiskEmoji() {
        Finding orange = finding("com.example:other-lib", "2.1.0", "3.0.0", 62, Severity.ORANGE,
            List.of(signal("REPO-CHANGED", 20, "URL changed")));
        String md = reporter.render(List.of(orange));
        assertThat(md).contains("### 🟠 MODERATE RISK — com.example:other-lib `2.1.0 → 3.0.0`");
    }

    @Test
    void orangeFinding_reviewRecommendation() {
        Finding orange = finding("a:b", "1.0", "2.0", 62, Severity.ORANGE,
            List.of(signal("REPO-CHANGED", 20, "ev")));
        assertThat(reporter.render(List.of(orange)))
            .contains("**Recommendation:** Review the changes before merging.");
    }

    // ── GREEN finding ─────────────────────────────────────────────────────────────

    @Test
    void greenFinding_safeOneLiner() {
        Finding green = finding("org.apache.commons:commons-lang3", "3.12.0", "3.13.0",
            4, Severity.GREEN, List.of());
        String md = reporter.render(List.of(green));
        assertThat(md).contains(
            "### 🟢 SAFE — org.apache.commons:commons-lang3 `3.12.0 → 3.13.0`");
        assertThat(md).contains("No behavioral anomalies detected.");
    }

    @Test
    void greenFinding_noSignalTable() {
        Finding green = finding("a:b", "1.0", "2.0", 4, Severity.GREEN, List.of());
        String md = reporter.render(List.of(green));
        assertThat(md).doesNotContain("| Signal | Detail |");
    }

    // ── YELLOW exclusion ──────────────────────────────────────────────────────────

    @Test
    void yellowFinding_excludedFromOutput() {
        Finding yellow = finding("a:b", "1.0", "2.0", 34, Severity.YELLOW,
            List.of(signal("DEP-EXPLOSION", 25, "dep count grew")));
        String md = reporter.render(List.of(yellow));

        assertThat(md).doesNotContain("YELLOW");
        assertThat(md).doesNotContain("a:b");          // dep not rendered
        assertThat(md).doesNotContain("| Signal |");   // no table for yellow
    }

    // ── footer ────────────────────────────────────────────────────────────────────

    @Test
    void footerPresent() {
        String md = reporter.render(List.of());
        assertThat(md).contains(
            "<sub>Powered by [Marshal](https://marshalhq.dev) · " +
            "[False positive?](https://github.com/marshal-hq/marshal/issues)</sub>");
    }

    // ── ordering ─────────────────────────────────────────────────────────────────

    @Test
    void redAppearsBeforeOrange() {
        Finding red    = finding("a:b", "1.0", "2.0", 87, Severity.RED,
            List.of(signal("SIG-DROPPED", 40, "ev")));
        Finding orange = finding("c:d", "2.0", "3.0", 62, Severity.ORANGE,
            List.of(signal("REPO-CHANGED", 20, "ev")));

        String md = reporter.render(List.of(orange, red)); // intentionally reversed
        assertThat(md.indexOf("HIGH RISK")).isLessThan(md.indexOf("MODERATE RISK"));
    }
}

package dev.marshalhq.cli;

import dev.marshalhq.core.*;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
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

    // ── actionable marker (drives Action comment suppression, §3.9 item 1) ──────────

    @Test
    void actionableMarker_falseWhenNothingFlaggedAdvisoryOrUnresolved() {
        Finding green = finding("a:b", null, "1.0", 4, Severity.GREEN, List.of());
        String md = reporter.render(List.of(green));
        assertThat(md).contains("<!-- marshal:actionable=false -->");
    }

    @Test
    void actionableMarker_falseOnEmptyDiff() {
        assertThat(reporter.render(List.of())).contains("<!-- marshal:actionable=false -->");
    }

    @Test
    void actionableMarker_trueWhenFlagged() {
        Finding red = finding("a:b", "1.0", "2.0", 87, Severity.RED,
                List.of(signal("SIG-DROPPED", 40, "ev")));
        assertThat(reporter.render(List.of(red))).contains("<!-- marshal:actionable=true -->");
    }

    @Test
    void actionableMarker_trueWhenOnlyAdvisory() {
        Finding yellow = finding("a:b", "1.0", "2.0", 34, Severity.YELLOW, List.of());
        assertThat(reporter.render(List.of(yellow))).contains("<!-- marshal:actionable=true -->");
    }

    @Test
    void actionableMarker_trueWhenOnlyUnresolved() {
        Finding unresolved = Finding.unresolved(new Coordinates("com.example", "lib-x", "UNRESOLVED"));
        assertThat(reporter.render(List.of(unresolved))).contains("<!-- marshal:actionable=true -->");
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
        assertThat(md).contains("**0 dependencies — 0 flagged, 0 safe**");
    }

    @Test
    void summaryLine_flaggedCount_isRedPlusOrange() {
        Finding red = finding("a:b", "1.0", "2.0", 87, Severity.RED,
                List.of(signal("SIG-DROPPED", 40, "sig dropped")));
        Finding orange = finding("c:d", "2.0", "3.0", 62, Severity.ORANGE,
                List.of(signal("REPO-CHANGED", 20, "url changed")));
        Finding green = finding("e:f", null, "1.0", 4, Severity.GREEN, List.of());

        String md = reporter.render(List.of(red, orange, green));
        // total = 3, flagged = red + orange = 2, no advisory, 1 safe
        assertThat(md).contains("**3 dependencies — 2 flagged, 1 safe**");
    }

    @Test
    void summaryLine_yellowAdvisoryClause_appearsWhenYellowPresent() {
        Finding yellow = finding("a:b", "1.0", "2.0", 34, Severity.YELLOW, List.of());
        Finding green = finding("c:d", null, "1.0", 4, Severity.GREEN, List.of());

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
        // a RED finding must not appear in the GREEN/SAFE section (guards against GREEN filter mutation)
        assertThat(md).doesNotContain("### 🟢 SAFE");
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
                signal("SIG-DROPPED", 40, "Prior 3 releases were signed")
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
    void greenFinding_excludedFromOutput() {
        Finding green = finding("org.apache.commons:commons-lang3", "3.12.0", "3.13.0",
                4, Severity.GREEN, List.of());
        String md = reporter.render(List.of(green));
        assertThat(md).doesNotContain("### 🟢 SAFE");
        assertThat(md).doesNotContain("commons-lang3");
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

    // ── suppressed exclusion ────────────────────────────────────────────────────────

    @Test
    void suppressedFinding_excludedFromPrComment() {
        Finding suppressed = finding("com.acme:internal-lib", "1.0", "2.0", 90, Severity.RED,
                List.of(signal("NEW-MAINTAINER", 55, "key changed")))
                .withSuppression(new SuppressionInfo("user", "Internal, vetted", "2026-12-22", "usman"));
        String md = reporter.render(List.of(suppressed));

        // A whitelisted finding must not surface in the PR comment, and must not flip
        // the actionable marker that gates whether a comment is posted at all.
        assertThat(md).doesNotContain("internal-lib");
        assertThat(md).doesNotContain("🔴 HIGH RISK");
        assertThat(md).contains("<!-- marshal:actionable=false -->");
    }

    // ── footer ────────────────────────────────────────────────────────────────────

    @Test
    void footerPresent() {
        String md = reporter.render(List.of());
        assertThat(md).contains(
                "<sub>Powered by [Marshal](https://marshalhq.dev) · " +
                        "[False positive?](https://github.com/marshal-hq/marshal/issues)</sub>");
    }

    // ── unresolved exclusion ──────────────────────────────────────────────────

    @Test
    void unresolvedFinding_notRenderedInAnySection() {
        Finding unresolved = Finding.unresolved(new Coordinates("com.example", "lib-x", "UNRESOLVED"));
        Finding green = finding("a:b", null, "1.0", 4, Severity.GREEN, List.of());

        String md = reporter.render(List.of(unresolved, green));

        // the unresolved dep is not rendered as a finding (no package GA / version leak)
        assertThat(md).doesNotContain("lib-x");
        // green dep must not appear as a finding section
        assertThat(md).doesNotContain("### 🟢 SAFE");
        assertThat(md).doesNotContain("a:b");
        // total = 2 (both counted), flagged 0, 1 safe; unresolved surfaced in its own note
        assertThat(md).contains("**2 dependencies — 0 flagged, 1 safe**");
        assertThat(md).contains("could not be fully resolved");
    }

    @Test
    void unresolvedFinding_listedWhenShowUnresolvedEnabled() {
        MarkdownReporter showUnres = new MarkdownReporter(false, true);
        Finding unresolved = Finding.unresolved(new Coordinates("com.example", "private-lib", "UNRESOLVED"));
        String md = showUnres.render(List.of(unresolved));

        assertThat(md).contains("could not be fully resolved");
        assertThat(md).contains("> - `com.example:private-lib`");
    }

    @Test
    void unresolvedFinding_notListedByDefault() {
        Finding unresolved = Finding.unresolved(new Coordinates("com.example", "private-lib", "UNRESOLVED"));
        String md = reporter.render(List.of(unresolved));

        assertThat(md).contains("could not be fully resolved");
        assertThat(md).doesNotContain("> - `com.example:private-lib`");
    }

    @Test
    void report_writesToProvidedWriter() {
        Finding green = finding("a:b", null, "1.0", 4, Severity.GREEN, List.of());
        StringWriter sw = new StringWriter();
        reporter.report(ScanReport.from(List.of(green)), new PrintWriter(sw));
        assertThat(sw.toString()).contains("<!-- marshal-bot -->");
    }

    // ── ordering ─────────────────────────────────────────────────────────────────

    @Test
    void redAppearsBeforeOrange() {
        Finding red = finding("a:b", "1.0", "2.0", 87, Severity.RED,
                List.of(signal("SIG-DROPPED", 40, "ev")));
        Finding orange = finding("c:d", "2.0", "3.0", 62, Severity.ORANGE,
                List.of(signal("REPO-CHANGED", 20, "ev")));

        String md = reporter.render(List.of(orange, red)); // intentionally reversed
        assertThat(md.indexOf("HIGH RISK")).isLessThan(md.indexOf("MODERATE RISK"));
    }
}

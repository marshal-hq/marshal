package dev.marshalhq.cli;

import dev.marshalhq.core.*;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalReporterTest {

    private static final int WIDTH = 80;
    private final TerminalReporter reporter = new TerminalReporter(WIDTH);

    // ── fixtures ────────────────────────────────────────────────────────────────

    private static Finding finding(String ga, String from, String to,
            int score, Severity level) {
        String[] parts = ga.split(":");
        Coordinates coords = new Coordinates(parts[0], parts[1], to);
        return new Finding(coords, from, to, score, level, List.of(), false, false);
    }

    private static Finding findingWithSignals(String ga, String from, String to,
            int score, Severity level, List<RuleResult> signals) {
        String[] parts = ga.split(":");
        Coordinates coords = new Coordinates(parts[0], parts[1], to);
        return new Finding(coords, from, to, score, level, signals, false, false);
    }

    private static Finding findingWithUnknown(String ga, String to) {
        String[] parts = ga.split(":");
        Coordinates coords = new Coordinates(parts[0], parts[1], to);
        return new Finding(coords, null, to, 0, Severity.GREEN, List.of(), false, true);
    }

    private static Finding unresolved(String ga) {
        String[] parts = ga.split(":");
        return Finding.unresolved(new Coordinates(parts[0], parts[1], "UNRESOLVED"));
    }

    private static Finding withUnexpandedSubtree(String ga, String to, Severity level) {
        return finding(ga, null, to, 0, level).withUnexpandedSubtree();
    }

    private String render(List<Finding> findings) {
        StringWriter sw = new StringWriter();
        reporter.report(ScanReport.from(findings), new PrintWriter(sw));
        return sw.toString();
    }

    // ── tests ────────────────────────────────────────────────────────────────────

    @Test
    void headerContainsProductVerbs() {
        String out = render(List.of());
        assertThat(out).contains("WATCH").contains("ANALYZE").contains("BLOCK");
    }

    @Test
    void headerUsesBoxDrawingDividers() {
        String out = render(List.of());
        assertThat(out).contains("─");
    }

    @Test
    void redFinding_rendersRedBadgeAndScore() {
        Finding red = finding("com.example:some-lib", "1.2.3", "2.0.0", 87, Severity.RED);
        String out = render(List.of(red));

        assertThat(out).contains("● RED");
        assertThat(out).contains("87/100");
        assertThat(out).contains("com.example:some-lib");
        assertThat(out).contains("1.2.3 → 2.0.0");
    }

    @Test
    void orangeFinding_rendersOrangeBadge() {
        Finding orange = finding("com.example:other-lib", "2.1.0", "3.0.0", 62, Severity.ORANGE);
        String out = render(List.of(orange));

        assertThat(out).contains("● ORANGE");
        assertThat(out).contains("62/100");
    }

    @Test
    void yellowFinding_notRenderedInDetailByDefault_shownAsAdvisoryCount() {
        Finding yellow = finding("com.example:yet-lib", "0.1.0", "0.2.0", 34, Severity.YELLOW);
        String out = render(List.of(yellow));

        // Advisory (YELLOW) is a summary count by default, never a detail row.
        assertThat(out).doesNotContain("● YELLOW");
        assertThat(out).doesNotContain("34/100");
        assertThat(out).contains("1 advisory");
    }

    @Test
    void yellowFinding_renderedInDetail_whenShowAdvisoryEnabled() {
        TerminalReporter showAdv = new TerminalReporter(WIDTH, true);
        Finding yellow = finding("com.example:yet-lib", "0.1.0", "0.2.0", 34, Severity.YELLOW);
        StringWriter sw = new StringWriter();
        showAdv.report(ScanReport.from(List.of(yellow)), new PrintWriter(sw));

        assertThat(sw.toString()).contains("● YELLOW");
        assertThat(sw.toString()).contains("34/100");
    }

    @Test
    void greenFinding_doesNotAppearInFindingsList() {
        Finding green = finding("com.example:safe-lib", "3.12.0", "3.13.0", 4, Severity.GREEN);
        String out = render(List.of(green));

        // GREEN passes silently — badge must not appear
        assertThat(out).doesNotContain("● GREEN");
        assertThat(out).doesNotContain("4/100");
    }

    @Test
    void mixedSeverity_findingsOrderedByScoreDescending() {
        Finding red = finding("com.example:a", "1.0", "2.0", 87, Severity.RED);
        Finding orange = finding("com.example:b", "2.0", "3.0", 62, Severity.ORANGE);
        Finding yellow = finding("com.example:c", "0.1", "0.2", 34, Severity.YELLOW);
        Finding green = finding("com.example:d", "3.0", "3.1", 4, Severity.GREEN);

        String out = render(List.of(green, yellow, orange, red)); // intentionally mixed order

        int posRed = out.indexOf("● RED");
        int posOrange = out.indexOf("● ORANGE");
        assertThat(posRed).isLessThan(posOrange);
        // YELLOW is advisory — a summary count, not a detail row.
        assertThat(out).doesNotContain("● YELLOW");
        assertThat(out).contains("1 advisory");
    }

    @Test
    void summaryLineContainsTotalAndFlaggedCount() {
        Finding red = finding("com.example:a", null, "1.0.0", 87, Severity.RED);
        Finding green = finding("com.example:b", null, "1.0.0", 4, Severity.GREEN);

        String out = render(List.of(red, green));

        // "2 dependencies — 1 flagged"
        assertThat(out).contains("2 dependencies");
        assertThat(out).contains("1 flagged");
    }

    @Test
    void firstSeenDep_noFromVersion_rowHasNoArrow() {
        Finding red = finding("com.example:new-lib", null, "1.0.0", 87, Severity.RED);
        String out = render(List.of(red));

        assertThat(out).contains("● RED");
        // The finding row itself must not contain → (no fromVersion); the header has → but not the row
        assertThat(out.lines()
                .filter(l -> l.contains("● RED"))
                .noneMatch(l -> l.contains("→")))
                .isTrue();
    }

    @Test
    void unresolvedDeps_renderedInOwnSection_notCountedAsFlagged() {
        Finding red = finding("com.example:a", "1.0", "2.0", 87, Severity.RED);
        Finding unres = unresolved("com.example:b");

        String out = render(List.of(red, unres));

        assertThat(out).contains("could not be fully resolved");
        assertThat(out).contains("manual review recommended");
        // Total = 2 deps, 1 flagged (unresolved is not flagged)
        assertThat(out).contains("1 flagged");
    }

    @Test
    void unresolvedDeps_notListedByDefault() {
        Finding unres = unresolved("com.example:private-lib");
        String out = render(List.of(unres));

        assertThat(out).contains("could not be fully resolved");
        // GA name must not appear as a list item without the flag
        assertThat(out).doesNotContain("· com.example:private-lib");
    }

    @Test
    void unresolvedDeps_listedWhenShowUnresolvedEnabled() {
        TerminalReporter showUnres = new TerminalReporter(WIDTH, false, true);
        Finding unres = unresolved("com.example:private-lib");
        StringWriter sw = new StringWriter();
        showUnres.report(ScanReport.from(List.of(unres)), new PrintWriter(sw));

        assertThat(sw.toString()).contains("could not be fully resolved");
        assertThat(sw.toString()).contains("· com.example:private-lib");
    }

    @Test
    void unexpandedSubtree_surfacesNotice_depStillScored() {
        Finding flagged = withUnexpandedSubtree("com.example:broken-descriptor", "1.0.0", Severity.GREEN);
        String out = render(List.of(flagged));

        // The dep itself is counted (scored normally) …
        assertThat(out).contains("1 dependency");
        // … but the unscanned subtree must be called out.
        assertThat(out).contains("could not be expanded");
        assertThat(out).contains("not scanned, manual review recommended");
    }

    @Test
    void unexpandedSubtree_gavListedWhenShowUnresolvedEnabled() {
        TerminalReporter showUnres = new TerminalReporter(WIDTH, false, true);
        Finding flagged = withUnexpandedSubtree("com.example:broken-descriptor", "1.0.0", Severity.GREEN);
        StringWriter sw = new StringWriter();
        showUnres.report(ScanReport.from(List.of(flagged)), new PrintWriter(sw));

        assertThat(sw.toString()).contains("· com.example:broken-descriptor:1.0.0");
    }

    @Test
    void noUnexpandedSubtrees_noticeIsAbsent() {
        Finding green = finding("com.example:a", null, "1.0", 0, Severity.GREEN);
        String out = render(List.of(green));

        assertThat(out).doesNotContain("could not be expanded");
    }

    @Test
    void unknownMetadata_surfacesIncompleteMetadataNotice() {
        Finding green = finding("com.example:a", null, "1.0", 0, Severity.GREEN);
        Finding unknown = findingWithUnknown("com.example:b", "2.0");

        String out = render(List.of(green, unknown));

        assertThat(out).contains("incomplete metadata");
        assertThat(out).contains("results may be partial");
    }

    @Test
    void noUnresolvedOrUnknown_noticesAreAbsent() {
        Finding green = finding("com.example:a", null, "1.0", 0, Severity.GREEN);
        String out = render(List.of(green));

        assertThat(out).doesNotContain("could not be fully resolved");
        assertThat(out).doesNotContain("incomplete metadata");
    }

    @Test
    void evidenceLinesAppearsIndentedBelowFindingRow() {
        RuleResult sig = new RuleResult(55, Severity.ORANGE,
                "Publisher signing key changed from [AAAA] to [BBBB]", "NEW-MAINTAINER");
        Finding f = findingWithSignals("com.example:risky-lib", "1.0.0", "2.0.0",
                55, Severity.ORANGE, List.of(sig));
        String out = render(List.of(f));

        int badgePos = out.indexOf("● ORANGE");
        int arrowPos = out.indexOf("↳");
        assertThat(arrowPos).isGreaterThan(badgePos);
        assertThat(out).contains("NEW-MAINTAINER");
        assertThat(out).contains("Publisher signing key changed from [AAAA] to [BBBB]");
    }

    @Test
    void signalWithBlankEvidence_notRendered() {
        RuleResult sig = new RuleResult(55, Severity.ORANGE, "", "SOME-RULE");
        Finding f = findingWithSignals("com.example:lib", "1.0", "2.0", 55, Severity.ORANGE, List.of(sig));
        String out = render(List.of(f));

        // The finding row renders, but a blank-evidence signal must not produce a ↳ line.
        assertThat(out).contains("● ORANGE");
        assertThat(out).doesNotContain("↳");
    }

    // ── suppressed findings ────────────────────────────────────────────────────────

    private static Finding suppressed(String ga, int score, Severity level, String reason) {
        String[] parts = ga.split(":");
        Coordinates coords = new Coordinates(parts[0], parts[1], "2.0.0");
        return new Finding(coords, "1.0.0", "2.0.0", score, level, List.of(), false, false)
                .withSuppression(new SuppressionInfo("user", reason, "2026-12-22", "usman"));
    }

    @Test
    void suppressedFinding_notInRiskListByDefault_summaryLineOnly() {
        Finding red = finding("com.example:real", "1.0", "2.0", 87, Severity.RED);
        Finding sup = suppressed("com.acme:internal-lib", 90, Severity.RED, "Internal, vetted");

        String out = render(List.of(red, sup));

        // The suppressed package never appears as a detail row.
        assertThat(out).doesNotContain("com.acme:internal-lib");
        assertThat(out).contains("1 finding suppressed by whitelist");
        assertThat(out).contains("--show-suppressed");
        // The real RED still gates and renders.
        assertThat(out).contains("com.example:real");
        assertThat(out).contains("1 flagged");
    }

    @Test
    void suppressedCountUsesPluralForm() {
        Finding s1 = suppressed("com.acme:a", 90, Severity.RED, "vetted");
        Finding s2 = suppressed("com.acme:b", 70, Severity.ORANGE, "vetted");
        String out = render(List.of(s1, s2));
        assertThat(out).contains("2 findings suppressed by whitelist");
    }

    @Test
    void noSuppressedFindings_noticeAbsent() {
        Finding red = finding("com.example:a", "1.0", "2.0", 87, Severity.RED);
        String out = render(List.of(red));
        assertThat(out).doesNotContain("suppressed by whitelist");
    }

    @Test
    void showSuppressed_expandsSuppressedFindingsWithReason() {
        TerminalReporter showSup = new TerminalReporter(WIDTH, false, false, true);
        Finding sup = suppressed("com.acme:internal-lib", 90, Severity.RED, "Internal, vetted by platform");
        StringWriter sw = new StringWriter();
        showSup.report(ScanReport.from(List.of(sup)), new PrintWriter(sw));

        String out = sw.toString();
        assertThat(out).contains("com.acme:internal-lib");
        assertThat(out).contains("Internal, vetted by platform");
        // The hint to use the flag is not shown when the flag is already on.
        assertThat(out).doesNotContain("--show-suppressed");
    }

    // ── introduced-by via line ─────────────────────────────────────────────────────

    private static Finding withPaths(Finding f, List<List<DependencyPathNode>> paths) {
        return f.withIntroducedBy(paths);
    }

    private static DependencyPathNode hop(String ga, String version, boolean direct) {
        String[] p = ga.split(":");
        return new DependencyPathNode(p[0], p[1], version, direct);
    }

    @Test
    void transitiveFinding_rendersViaLineWithShortestPath() {
        Finding f = withPaths(finding("com.foo:bar", null, "2.0.0", 60, Severity.ORANGE),
                List.of(List.of(hop("com.example:dep-b", "1.4.0", true),
                        hop("com.foo:bar", "2.0.0", false))));

        String out = render(List.of(f));
        assertThat(out).contains(
                "via  com.example:dep-b:1.4.0 (direct) -> com.foo:bar:2.0.0");
        assertThat(out).doesNotContain("other path");
    }

    @Test
    void multiplePaths_viaLineShowsShortestPlusCount_othersNotPrinted() {
        Finding f = withPaths(finding("com.foo:bar", null, "2.0.0", 60, Severity.ORANGE),
                List.of(
                        List.of(hop("com.example:dep-b", "1.4.0", true), hop("com.foo:bar", "2.0.0", false)),
                        List.of(hop("com.example:dep-c", "3.0.0", true), hop("com.foo:bar", "2.0.0", false)),
                        List.of(hop("com.example:dep-c", "3.0.0", true), hop("com.mid:x", "1.0.0", false),
                                hop("com.foo:bar", "2.0.0", false))));

        String out = render(List.of(f));
        assertThat(out).contains(
                "via  com.example:dep-b:1.4.0 (direct) -> com.foo:bar:2.0.0   (+2 other paths)");
        // Only the shortest path is printed; the alternates never appear.
        assertThat(out).doesNotContain("dep-c");
    }

    @Test
    void singleExtraPath_usesSingularCount() {
        Finding f = withPaths(finding("com.foo:bar", null, "2.0.0", 60, Severity.ORANGE),
                List.of(
                        List.of(hop("com.a:a", "1.0.0", true), hop("com.foo:bar", "2.0.0", false)),
                        List.of(hop("com.b:b", "1.0.0", true), hop("com.foo:bar", "2.0.0", false))));

        assertThat(render(List.of(f))).contains("(+1 other path)");
    }

    @Test
    void declaredDirectFinding_noViaLine() {
        // The user already sees this dep in their build file — `via <self> (direct)` is noise.
        Finding f = withPaths(finding("com.example:dep-b", null, "1.4.0", 60, Severity.ORANGE),
                List.of(List.of(hop("com.example:dep-b", "1.4.0", true))));

        assertThat(render(List.of(f))).doesNotContain("via");
    }

    @Test
    void noPathData_noViaLine() {
        Finding f = finding("com.foo:bar", null, "2.0.0", 60, Severity.ORANGE);
        assertThat(render(List.of(f))).doesNotContain("via");
    }

    @Test
    void dividerIsFullWidth() {
        String out = render(List.of());
        // At least one line of ─ chars that reaches full width
        assertThat(out.lines()
                .anyMatch(l -> l.chars().allMatch(c -> c == '─') && l.length() == WIDTH))
                .isTrue();
    }

    @Test
    void scoreIsOnSameLineAsBadge() {
        Finding red = finding("com.example:some-lib", "1.0.0", "2.0.0", 87, Severity.RED);
        String out = render(List.of(red));

        // The line containing the badge must also contain the score
        assertThat(out.lines()
                .filter(l -> l.contains("● RED"))
                .anyMatch(l -> l.contains("87/100")))
                .isTrue();
    }
}

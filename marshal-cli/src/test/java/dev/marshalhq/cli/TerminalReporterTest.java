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

    private String render(List<Finding> findings) {
        StringWriter sw = new StringWriter();
        reporter.report(findings, new PrintWriter(sw));
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
    void yellowFinding_rendersYellowBadge() {
        Finding yellow = finding("com.example:yet-lib", "0.1.0", "0.2.0", 34, Severity.YELLOW);
        String out = render(List.of(yellow));

        assertThat(out).contains("● YELLOW");
        assertThat(out).contains("34/100");
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
        int posYellow = out.indexOf("● YELLOW");
        assertThat(posRed).isLessThan(posOrange).isLessThan(posYellow);
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
        RuleResult sig = new RuleResult(35, Severity.ORANGE,
                "Publisher signing key changed from [AAAA] to [BBBB]", "NEW-MAINTAINER");
        Finding f = findingWithSignals("com.example:risky-lib", "1.0.0", "2.0.0",
                35, Severity.YELLOW, List.of(sig));
        String out = render(List.of(f));

        int badgePos = out.indexOf("● YELLOW");
        int arrowPos = out.indexOf("↳");
        assertThat(arrowPos).isGreaterThan(badgePos);
        assertThat(out).contains("NEW-MAINTAINER");
        assertThat(out).contains("Publisher signing key changed from [AAAA] to [BBBB]");
    }

    @Test
    void signalWithBlankEvidence_notRendered() {
        RuleResult sig = new RuleResult(10, Severity.YELLOW, "", "SOME-RULE");
        Finding f = findingWithSignals("com.example:lib", "1.0", "2.0", 10, Severity.YELLOW, List.of(sig));
        String out = render(List.of(f));

        assertThat(out).doesNotContain("↳");
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

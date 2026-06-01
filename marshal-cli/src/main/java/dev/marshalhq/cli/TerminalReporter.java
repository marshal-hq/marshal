package dev.marshalhq.cli;

import dev.marshalhq.core.Finding;
import dev.marshalhq.core.Severity;
import picocli.CommandLine.Help.Ansi;

import java.io.PrintWriter;
import java.util.Comparator;
import java.util.List;

/**
 * ANSI terminal reporter. Follows DESIGN-SYSTEM.md §8 verbatim.
 *
 * Badge strings:
 *   RED    → @|red ● RED   |@
 *   ORANGE → @|yellow ● ORANGE |@
 *   YELLOW → @|yellow ● YELLOW |@
 *   GREEN  → @|green ● GREEN  |@  (not rendered — GREEN passes silently)
 *
 * Score is right-aligned to terminal width on the same row as the package name.
 * Section headers use ─ box-drawing characters at full terminal width.
 */
public class TerminalReporter implements Reporter {

    private static final String BADGE_RED    = "@|red ● RED   |@";
    private static final String BADGE_ORANGE = "@|yellow ● ORANGE |@";
    private static final String BADGE_YELLOW = "@|yellow ● YELLOW |@";

    private final int width;

    public TerminalReporter() {
        this(detectWidth());
    }

    /** Package-private: use a fixed width in tests for deterministic assertions. */
    TerminalReporter(int width) {
        this.width = width;
    }

    @Override
    public void report(List<Finding> findings, PrintWriter out) {
        // ── Header ──────────────────────────────────────────────────────────────
        out.println(divider("MARSHAL SCAN"));
        out.println(Ansi.AUTO.string(
            "@|yellow WATCH|@ → @|yellow ANALYZE|@ → @|red BLOCK|@"));
        out.println();

        // ── Flagged findings (YELLOW / ORANGE / RED), highest score first ───────
        List<Finding> flagged = findings.stream()
            .filter(f -> !f.isUnresolved()
                && f.riskLevel() != null
                && f.riskLevel() != Severity.GREEN)
            .sorted(Comparator.comparingInt(Finding::riskScore).reversed())
            .toList();

        for (Finding f : flagged) {
            out.println(Ansi.AUTO.string(findingRow(f)));
        }

        if (!flagged.isEmpty()) {
            out.println();
        }

        // ── Summary divider ──────────────────────────────────────────────────────
        out.println(divider(null));

        long flaggedCount   = flagged.size();
        long unresolvedCount = findings.stream().filter(Finding::isUnresolved).count();
        long totalCount      = findings.size();

        out.printf("%d %s — %d flagged%n",
            totalCount,
            totalCount == 1 ? "dependency" : "dependencies",
            flaggedCount);

        // ── UNRESOLVED section (S06) ─────────────────────────────────────────────
        if (unresolvedCount > 0) {
            out.printf("%n%d %s could not be fully resolved — manual review recommended%n",
                unresolvedCount,
                unresolvedCount == 1 ? "dependency" : "dependencies");
        }

        // ── UNKNOWN metadata notice ──────────────────────────────────────────────
        long unknownCount = findings.stream()
            .filter(f -> !f.isUnresolved() && f.hasUnknownMetadata())
            .count();
        if (unknownCount > 0) {
            out.printf("%d %s had incomplete metadata — results may be partial%n",
                unknownCount,
                unknownCount == 1 ? "dependency" : "dependencies");
        }
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private String findingRow(Finding f) {
        String badge   = badgeMarkup(f.riskLevel());
        String from    = f.fromVersion() != null ? f.fromVersion() + " → " : "";
        String content = f.coordinates().toGa() + "  " + from + f.toVersion();
        String score   = f.riskScore() + "/100";

        // Visible badge length (strip markup for width arithmetic)
        int visibleBadge = Ansi.OFF.string(badge).length();
        int leftLen      = visibleBadge + 1 + content.length(); // badge + space + content
        int pad          = Math.max(2, width - leftLen - score.length());

        return badge + " " + content + " ".repeat(pad) + score;
    }

    private String divider(String label) {
        if (label == null || label.isEmpty()) {
            return "─".repeat(width);
        }
        String inner = " " + label + " ";
        int sideLen  = (width - inner.length()) / 2;
        int rightLen = width - sideLen - inner.length();
        return "─".repeat(Math.max(0, sideLen)) + inner + "─".repeat(Math.max(0, rightLen));
    }

    private static String badgeMarkup(Severity level) {
        return switch (level) {
            case RED    -> BADGE_RED;
            case ORANGE -> BADGE_ORANGE;
            case YELLOW -> BADGE_YELLOW;
            case GREEN  -> "@|green ● GREEN  |@"; // included for completeness; not rendered
        };
    }

    private static int detectWidth() {
        String cols = System.getenv("COLUMNS");
        if (cols != null) {
            try { return Math.max(40, Integer.parseInt(cols.trim())); }
            catch (NumberFormatException ignored) {}
        }
        return 80;
    }
}

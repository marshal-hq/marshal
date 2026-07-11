package dev.marshalhq.cli;

import dev.marshalhq.core.Finding;
import dev.marshalhq.core.RuleResult;
import dev.marshalhq.core.Severity;
import picocli.CommandLine.Help.Ansi;

import java.io.PrintWriter;
import java.util.List;

/**
 * ANSI terminal reporter. Follows DESIGN-SYSTEM.md §8 verbatim.
 * <p>
 * Badge strings:
 * RED    → @|bold,red ● RED   |@
 * ORANGE → @|fg(208) ● ORANGE |@   (256-color orange; falls back to default on basic terminals)
 * YELLOW → @|yellow ● YELLOW |@
 * GREEN  → @|green ● GREEN  |@  (not rendered — GREEN passes silently)
 * <p>
 * Score is right-aligned to terminal width on the same row as the package name.
 * Section headers use ─ box-drawing characters at full terminal width.
 */
public class TerminalReporter implements Reporter {

    private static final String BADGE_RED = "@|bold,red ● RED   |@";
    private static final String BADGE_ORANGE = "@|fg(208) ● ORANGE |@";
    private static final String BADGE_YELLOW = "@|yellow ● YELLOW |@";

    private final int width;
    private final boolean showAdvisory;
    private final boolean showUnresolved;
    private final boolean showSuppressed;

    public TerminalReporter() {
        this(detectWidth(), false, false, false);
    }

    public TerminalReporter(boolean showAdvisory) {
        this(detectWidth(), showAdvisory, false, false);
    }

    public TerminalReporter(boolean showAdvisory, boolean showUnresolved) {
        this(detectWidth(), showAdvisory, showUnresolved, false);
    }

    public TerminalReporter(boolean showAdvisory, boolean showUnresolved, boolean showSuppressed) {
        this(detectWidth(), showAdvisory, showUnresolved, showSuppressed);
    }

    /**
     * Package-private: use a fixed width in tests for deterministic assertions.
     */
    TerminalReporter(int width) {
        this(width, false, false, false);
    }

    TerminalReporter(int width, boolean showAdvisory) {
        this(width, showAdvisory, false, false);
    }

    TerminalReporter(int width, boolean showAdvisory, boolean showUnresolved) {
        this(width, showAdvisory, showUnresolved, false);
    }

    TerminalReporter(int width, boolean showAdvisory, boolean showUnresolved, boolean showSuppressed) {
        this.width = width;
        this.showAdvisory = showAdvisory;
        this.showUnresolved = showUnresolved;
        this.showSuppressed = showSuppressed;
    }

    @Override
    public void report(ScanReport report, PrintWriter out) {
        // ── Header ──────────────────────────────────────────────────────────────
        out.println(divider("MARSHAL SCAN"));
        out.println(Ansi.AUTO.string(
                "@|yellow WATCH|@ → @|yellow ANALYZE|@ → @|red BLOCK|@"));
        out.println();

        // ── Findings rendered in full, highest score first ───────────────────────
        // Shared render policy: RED/ORANGE always; YELLOW only with --show-advisory.
        List<Finding> shown = report.detail(showAdvisory);

        for (Finding f : shown) {
            out.println(Ansi.AUTO.string(findingRow(f)));
            for (RuleResult signal : f.signals()) {
                if (signal.evidence() != null && !signal.evidence().isBlank()) {
                    out.println(Ansi.AUTO.string(evidenceLine(signal)));
                }
            }
        }

        if (!shown.isEmpty()) {
            out.println();
        }

        // ── Summary divider ──────────────────────────────────────────────────────
        out.println(divider(null));

        long totalCount = report.total();
        out.printf("%d %s — %s%n",
                totalCount,
                totalCount == 1 ? "dependency" : "dependencies",
                report.summaryClause());

        // ── UNRESOLVED section (S06) ─────────────────────────────────────────────
        long unresolvedCount = report.unresolved().size();
        if (unresolvedCount > 0) {
            out.printf("%n%d %s could not be fully resolved — manual review recommended%n",
                    unresolvedCount,
                    unresolvedCount == 1 ? "dependency" : "dependencies");
            if (showUnresolved) {
                for (Finding f : report.unresolved()) {
                    out.println(Ansi.AUTO.string(
                            "@|fg(245)  · " + f.coordinates().toGa() + "|@"));
                }
            }
        }

        // ── Unexpanded-subtree notice (S06: an unscanned subtree must never look clean) ──
        long unexpandedCount = report.unexpandedSubtreeCount();
        if (unexpandedCount > 0) {
            out.printf("%n%d %s could not be expanded — %s not scanned, manual review recommended%n",
                    unexpandedCount,
                    unexpandedCount == 1 ? "dependency subtree" : "dependency subtrees",
                    unexpandedCount == 1 ? "the dependencies beneath it were" : "the dependencies beneath them were");
            if (showUnresolved) {
                for (Finding f : report.unexpandedSubtrees()) {
                    out.println(Ansi.AUTO.string(
                            "@|fg(245)  · " + f.coordinates().toGav() + "|@"));
                }
            }
        }

        // ── UNKNOWN metadata notice ──────────────────────────────────────────────
        long unknownCount = report.unknownMetadataCount();
        if (unknownCount > 0) {
            out.printf("%d %s had incomplete metadata — results may be partial%n",
                    unknownCount,
                    unknownCount == 1 ? "dependency" : "dependencies");
        }

        // ── SUPPRESSED notice ────────────────────────────────────────────────────
        // Default view: a single line, so the whitelist's noise reduction holds.
        // --show-suppressed: expand each suppressed finding for active auditing.
        long suppressedCount = report.suppressedCount();
        if (suppressedCount > 0) {
            if (showSuppressed) {
                out.println();
                out.printf("%d %s suppressed by whitelist:%n",
                        suppressedCount, suppressedCount == 1 ? "finding" : "findings");
                for (Finding f : report.suppressed()) {
                    out.println(Ansi.AUTO.string(findingRow(f)));
                    out.println(Ansi.AUTO.string(suppressionLine(f)));
                }
            }
            else {
                out.printf("%d %s suppressed by whitelist (--show-suppressed to view)%n",
                        suppressedCount, suppressedCount == 1 ? "finding" : "findings");
            }
        }
    }

    private static String suppressionLine(Finding f) {
        var info = f.suppression();
        String list = info != null ? info.matchedList() : "?";
        String reason = info != null && info.reason() != null ? info.reason() : "";
        return "@|fg(245)     ↳ suppressed by " + list + " whitelist — " + reason + "|@";
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private String findingRow(Finding f) {
        String badge = badgeMarkup(f.riskLevel());
        String from = f.fromVersion() != null ? f.fromVersion() + " → " : "";
        String content = f.coordinates().toGa() + "  " + from + f.toVersion();
        String score = f.riskScore() + "/100";

        // Visible badge length (strip markup for width arithmetic)
        int visibleBadge = Ansi.OFF.string(badge).length();
        int leftLen = visibleBadge + 1 + content.length(); // badge + space + content
        int pad = Math.max(2, width - leftLen - score.length());

        return badge + " " + content + " ".repeat(pad) + score;
    }

    private static String evidenceLine(RuleResult signal) {
        String coloredId = switch (signal.severity()) {
            case RED    -> "@|bold,red " + signal.ruleId() + "|@";
            case ORANGE -> "@|fg(208) " + signal.ruleId() + "|@";
            case YELLOW -> "@|yellow " + signal.ruleId() + "|@";
            case GREEN  -> signal.ruleId();
        };
        return "    ↳ " + coloredId + "  " + signal.evidence();
    }

    private String divider(String label) {
        if (label == null || label.isEmpty()) {
            return "─".repeat(width);
        }
        String inner = " " + label + " ";
        int sideLen = (width - inner.length()) / 2;
        int rightLen = width - sideLen - inner.length();
        return "─".repeat(Math.max(0, sideLen)) + inner + "─".repeat(Math.max(0, rightLen));
    }

    private static String badgeMarkup(Severity level) {
        return switch (level) {
            case RED -> BADGE_RED;
            case ORANGE -> BADGE_ORANGE;
            case YELLOW -> BADGE_YELLOW;
            case GREEN -> "@|green ● GREEN  |@"; // included for completeness; not rendered
        };
    }

    private static int detectWidth() {
        String cols = System.getenv("COLUMNS");
        if (cols != null) {
            try {
                return Math.max(40, Integer.parseInt(cols.trim()));
            }
            catch (NumberFormatException ignored) {
            }
        }
        return 80;
    }
}

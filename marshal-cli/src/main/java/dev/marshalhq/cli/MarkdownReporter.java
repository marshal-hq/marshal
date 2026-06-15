package dev.marshalhq.cli;

import java.io.PrintWriter;
import java.util.List;

import dev.marshalhq.core.Finding;
import dev.marshalhq.core.RuleResult;

/**
 * Markdown renderer — pure function: List<Finding> → GitHub Flavored Markdown.
 * Zero GitHub coupling; reused by both scan (--output md) and diff.
 * <p>
 * Render policy (shared with every other reporter; see {@link ScanReport#detail}):
 * RED    → ### 🔴 HIGH RISK     — full signal table + "Do not merge" recommendation
 * ORANGE → ### 🟠 MODERATE RISK — full signal table + "Review before merging" recommendation
 * YELLOW → ### 🟡 ADVISORY      — rendered in full only with --show-advisory; otherwise a
 *          count in the summary line
 * GREEN  → summary count only, never rendered in detail
 * <p>
 * First line is always <!-- marshal-bot --> for idempotent PR comment editing (Block 5).
 */
public class MarkdownReporter implements Reporter {

    private static final String FOOTER =
            "<sub>Powered by [Marshal](https://marshalhq.dev) · " +
                    "[False positive?](https://github.com/marshal-hq/marshal/issues)</sub>";

    private final boolean showAdvisory;
    private final boolean showUnresolved;

    public MarkdownReporter() {
        this(false, false);
    }

    public MarkdownReporter(boolean showAdvisory) {
        this(showAdvisory, false);
    }

    public MarkdownReporter(boolean showAdvisory, boolean showUnresolved) {
        this.showAdvisory = showAdvisory;
        this.showUnresolved = showUnresolved;
    }

    @Override
    public void report(ScanReport report, PrintWriter out) {
        out.print(render(report));
    }

    /** Convenience for callers/tests holding a raw finding list. */
    public String render(List<Finding> findings) {
        return render(ScanReport.from(findings));
    }

    /**
     * Pure render method — returns the full markdown string.
     * <p>
     * Shared render policy (identical across all reporters): RED/ORANGE always render
     * in full; YELLOW (advisory) renders in full only when {@code --show-advisory} is
     * set, otherwise it is a count in the summary; GREEN is a summary count; unresolved
     * deps are reported in a trailing note. The summary line is {@code N dependencies —
     * X flagged[, Y advisory], Z safe}.
     */
    public String render(ScanReport report) {
        StringBuilder sb = new StringBuilder();

        // Hidden idempotency marker — must be the very first line
        sb.append("<!-- marshal-bot -->\n");
        sb.append("## 🛡 Marshal Dependency Analysis\n");
        sb.append("\n");
        sb.append("**").append(report.total()).append(" ")
                .append(report.total() == 1 ? "dependency" : "dependencies")
                .append(" — ").append(report.summaryClause()).append("**");
        sb.append("\n\n---\n\n");

        // Findings rendered in full — already sorted highest score first.
        report.detail(showAdvisory).forEach(f -> {
            appendFinding(sb, f);
            sb.append("\n---\n\n");
        });

        if (report.unresolvedCount() > 0) {
            sb.append("> ⚠️ ").append(report.unresolvedCount())
                    .append(" ").append(report.unresolvedCount() == 1 ? "dependency" : "dependencies")
                    .append(" could not be fully resolved — manual review recommended.\n");
            if (showUnresolved) {
                for (Finding f : report.unresolved()) {
                    sb.append("> - `").append(f.coordinates().toGa()).append("`\n");
                }
            }
            sb.append("\n");
        }

        sb.append(FOOTER).append("\n");
        return sb.toString();
    }

    // ── section builders ──────────────────────────────────────────────────────────

    private static void appendFinding(StringBuilder sb, Finding f) {
        String heading = switch (f.riskLevel()) {
            case RED -> "### 🔴 HIGH RISK";
            case ORANGE -> "### 🟠 MODERATE RISK";
            case YELLOW -> "### 🟡 ADVISORY";
            case GREEN -> "### 🟢 SAFE"; // never rendered in detail; kept exhaustive
        };
        sb.append(heading).append(" — ").append(f.coordinates().toGa())
                .append(" `").append(versionSpan(f)).append("`\n");
        sb.append("**Risk score: ").append(f.riskScore()).append("/100**\n\n");

        // Signal table
        sb.append("| Signal | Detail |\n");
        sb.append("|--------|--------|\n");
        for (RuleResult s : f.signals()) {
            sb.append("| ").append(signalName(s.ruleId()))
                    .append(" | ").append(escape(s.evidence()))
                    .append(" |\n");
        }
        sb.append("\n");

        // Recommendation
        String rec = switch (f.riskLevel()) {
            case RED -> "**Recommendation:** Do not merge. Review the signals above before upgrading.";
            case ORANGE -> "**Recommendation:** Review the changes before merging. This may be a legitimate update.";
            default -> "**Recommendation:** Advisory only — review if relevant, not blocking.";
        };
        sb.append(rec).append("\n");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private static String versionSpan(Finding f) {
        return f.fromVersion() != null
                ? f.fromVersion() + " → " + f.toVersion()
                : f.toVersion();
    }

    private static String signalName(String ruleId) {
        if (ruleId == null) {
            return "Unknown";
        }
        return switch (ruleId) {
            case "MISSING-SIG" -> "Signature missing";
            case "SIG-DROPPED" -> "Signature dropped";
            case "MAJOR-JUMP" -> "Major version jump";
            case "NEW-MAINTAINER" -> "Maintainer changed";
            case "DEP-EXPLOSION" -> "Dependency explosion";
            case "REPO-CHANGED" -> "Repository URL changed";
            case "YANKED" -> "Version yanked";
            default -> ruleId;
        };
    }

    /**
     * Escape pipe characters inside table cells.
     */
    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|");
    }
}

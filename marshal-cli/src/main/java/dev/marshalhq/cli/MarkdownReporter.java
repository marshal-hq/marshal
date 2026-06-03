package dev.marshalhq.cli;

import dev.marshalhq.core.Finding;
import dev.marshalhq.core.RuleResult;
import dev.marshalhq.core.Severity;

import java.io.PrintWriter;
import java.util.Comparator;
import java.util.List;

/**
 * Markdown renderer — pure function: List<Finding> → GitHub Flavored Markdown.
 * Zero GitHub coupling; reused by both scan (--output md) and diff.
 * <p>
 * Follows DESIGN-SYSTEM.md §6.6 verbatim:
 * RED    → ### 🔴 HIGH RISK     — full signal table + "Do not merge" recommendation
 * ORANGE → ### 🟠 MODERATE RISK — full signal table + "Review before merging" recommendation
 * GREEN  → ### 🟢 SAFE          — one-liner, no signal table
 * YELLOW → excluded from output; count surfaced in advisory clause of summary line
 * <p>
 * First line is always <!-- marshal-bot --> for idempotent PR comment editing (Block 5).
 */
public class MarkdownReporter implements Reporter {

    private static final String FOOTER =
            "<sub>Powered by [Marshal](https://marshalhq.dev) · " +
                    "[False positive?](https://github.com/marshal-hq/marshal/issues)</sub>";

    @Override
    public void report(List<Finding> findings, PrintWriter out) {
        out.print(render(findings));
    }

    /**
     * Pure render method — returns the full markdown string.
     */
    public String render(List<Finding> findings) {
        List<Finding> visible = findings.stream()
                .filter(f -> !f.isUnresolved() && f.riskLevel() != Severity.YELLOW)
                .toList();

        long red = countLevel(findings, Severity.RED);
        long orange = countLevel(findings, Severity.ORANGE);
        long yellow = countLevel(findings, Severity.YELLOW);
        long flagged = red + orange;

        StringBuilder sb = new StringBuilder();

        // Hidden idempotency marker — must be the very first line
        sb.append("<!-- marshal-bot -->\n");
        sb.append("## 🛡 Marshal Dependency Analysis\n");
        sb.append("\n");
        sb.append(summaryLine(visible.size(), flagged, yellow));
        sb.append("\n\n---\n\n");

        // RED and ORANGE findings — highest score first
        visible.stream()
                .filter(f -> f.riskLevel() == Severity.RED || f.riskLevel() == Severity.ORANGE)
                .sorted(Comparator.comparingInt(Finding::riskScore).reversed())
                .forEach(f -> {
                    appendFlaggedFinding(sb, f);
                    sb.append("\n---\n\n");
                });

        // GREEN findings — one-liners
        visible.stream()
                .filter(f -> f.riskLevel() == Severity.GREEN)
                .forEach(f -> {
                    appendSafeFinding(sb, f);
                    sb.append("\n---\n\n");
                });

        sb.append(FOOTER).append("\n");
        return sb.toString();
    }

    // ── section builders ──────────────────────────────────────────────────────────

    private static void appendFlaggedFinding(StringBuilder sb, Finding f) {
        String heading = f.riskLevel() == Severity.RED
                ? "### 🔴 HIGH RISK"
                : "### 🟠 MODERATE RISK";
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
        String rec = f.riskLevel() == Severity.RED
                ? "**Recommendation:** Do not merge. Review the signals above before upgrading."
                : "**Recommendation:** Review the changes before merging. This may be a legitimate update.";
        sb.append(rec).append("\n");
    }

    private static void appendSafeFinding(StringBuilder sb, Finding f) {
        sb.append("### 🟢 SAFE — ").append(f.coordinates().toGa())
                .append(" `").append(versionSpan(f)).append("`\n");
        sb.append("Risk score: ").append(f.riskScore())
                .append("/100. No behavioral anomalies detected.\n");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private static String summaryLine(long total, long flagged, long advisory) {
        String base = "**" + total + " " + (total == 1 ? "update" : "updates") +
                " — " + flagged + " flagged";
        if (advisory > 0) {
            base += ", " + advisory + " advisory";
        }
        return base + "**";
    }

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

    private static long countLevel(List<Finding> findings, Severity level) {
        return findings.stream()
                .filter(f -> !f.isUnresolved() && f.riskLevel() == level)
                .count();
    }
}

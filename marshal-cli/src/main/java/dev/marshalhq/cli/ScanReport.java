package dev.marshalhq.cli;

import dev.marshalhq.core.Finding;
import dev.marshalhq.core.Severity;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The single classification of a scan's findings, computed once and shared by
 * every reporter and by the exit-code logic.
 * <p>
 * Reporters decide how to <em>format</em> these buckets for their surface (ANSI,
 * Markdown, JSON); they must never re-derive what "flagged" / "advisory" / "safe"
 * mean. Keeping that definition here is what stops the three surfaces from drifting
 * into three different answers for the same scan.
 * <ul>
 *   <li>{@code flagged}    = RED + ORANGE — render as full findings; gate PR comments / CI.</li>
 *   <li>{@code advisory}   = YELLOW — surfaced as a count only; detail lives in {@code --output json}.</li>
 *   <li>{@code safe}       = GREEN.</li>
 *   <li>{@code unresolved} = version could not be determined; never counted as safe.</li>
 * </ul>
 * The {@code flagged} and {@code advisory} lists are sorted by risk score descending.
 */
public record ScanReport(
        List<Finding> all,         // every finding, original order (JSON emits all)
        List<Finding> flagged,     // RED + ORANGE, score desc
        List<Finding> advisory,    // YELLOW, score desc
        List<Finding> safe,        // GREEN
        List<Finding> unresolved
) {

    public static ScanReport from(List<Finding> findings) {
        List<Finding> flagged = bySeverityDesc(findings, EnumSet.of(Severity.RED, Severity.ORANGE));
        List<Finding> advisory = bySeverityDesc(findings, EnumSet.of(Severity.YELLOW));
        List<Finding> safe = findings.stream()
                .filter(f -> !f.isUnresolved() && f.riskLevel() == Severity.GREEN)
                .toList();
        List<Finding> unresolved = findings.stream()
                .filter(Finding::isUnresolved)
                .toList();
        return new ScanReport(List.copyOf(findings), flagged, advisory, safe, unresolved);
    }

    /** Total dependencies scanned (includes unresolved). */
    public int total() {
        return all.size();
    }

    public int flaggedCount() {
        return flagged.size();
    }

    public int advisoryCount() {
        return advisory.size();
    }

    public int safeCount() {
        return safe.size();
    }

    public int unresolvedCount() {
        return unresolved.size();
    }

    /**
     * The findings every reporter renders in full detail, in one place so the three
     * surfaces stay identical. RED/ORANGE always; YELLOW (advisory) only when the
     * caller opts in via {@code --show-advisory}. GREEN is never rendered in detail.
     * Both sub-lists are pre-sorted by score descending, and because every flagged
     * score (≥51) outranks every advisory score (≤50) the concatenation stays sorted.
     */
    public List<Finding> detail(boolean showAdvisory) {
        if (!showAdvisory) {
            return flagged;
        }
        return Stream.concat(flagged.stream(), advisory.stream()).toList();
    }

    /**
     * The risk-bucket breakdown shown identically in every reporter's summary line:
     * {@code "1 flagged, 4 advisory, 172 safe"}. The advisory clause is omitted when
     * there are no YELLOW findings; unresolved deps are reported separately.
     */
    public String summaryClause() {
        StringBuilder sb = new StringBuilder();
        sb.append(flaggedCount()).append(" flagged");
        if (advisoryCount() > 0) {
            sb.append(", ").append(advisoryCount()).append(" advisory");
        }
        sb.append(", ").append(safeCount()).append(" safe");
        return sb.toString();
    }

    /** Count of resolved findings at a given severity (unresolved excluded). */
    public long count(Severity level) {
        return all.stream()
                .filter(f -> !f.isUnresolved() && f.riskLevel() == level)
                .count();
    }

    /**
     * Findings whose transitive subtree could not be expanded (descriptor unreadable):
     * the dep itself is scored, but everything beneath it is unscanned. Reported in its
     * own notice, like unresolved — an unscanned subtree must never look clean (S06).
     */
    public List<Finding> unexpandedSubtrees() {
        return all.stream()
                .filter(Finding::hasUnexpandedSubtree)
                .toList();
    }

    public int unexpandedSubtreeCount() {
        return unexpandedSubtrees().size();
    }

    /** Resolved findings whose metadata was incomplete (results may be partial). */
    public long unknownMetadataCount() {
        return all.stream()
                .filter(f -> !f.isUnresolved() && f.hasUnknownMetadata())
                .count();
    }

    /** Worst resolved severity present, used to gate the exit code. */
    public Optional<Severity> worstSeverity() {
        return all.stream()
                .filter(f -> !f.isUnresolved() && f.riskLevel() != null)
                .map(Finding::riskLevel)
                .max(Comparator.comparingInt(Severity::ordinal));
    }

    private static List<Finding> bySeverityDesc(List<Finding> findings, EnumSet<Severity> levels) {
        return findings.stream()
                .filter(f -> !f.isUnresolved() && f.riskLevel() != null && levels.contains(f.riskLevel()))
                .sorted(Comparator.comparingInt(Finding::riskScore).reversed())
                .toList();
    }
}

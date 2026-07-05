package dev.marshalhq.core;

import java.util.List;

/**
 * Fully evaluated result for one dependency. Used by all reporters (terminal, JSON, markdown)
 * and by the diff command.
 * <p>
 * When {@code isUnresolved} is true the version could not be determined; {@code riskLevel}
 * is null and the dep must never be silently dropped or counted as GREEN.
 * <p>
 * When {@code hasUnknownMetadata} is true at least one metadata field could not be fetched
 * (e.g. signature status is UNKNOWN); results may be partial.
 * <p>
 * When {@code suppressed} is true a whitelist matched this finding's GAV. It stays in
 * the audit record (with {@code suppression} metadata) but drops out of the risk list,
 * the exit code, and Slack.
 */
public record Finding(
    Coordinates coordinates,
    String fromVersion,          // previous version; null = first-ever release
    String toVersion,            // version from the project POM
    int riskScore,               // 0–100; 0 when isUnresolved
    Severity riskLevel,          // null when isUnresolved
    List<RuleResult> signals,
    boolean isUnresolved,
    boolean hasUnknownMetadata,
    boolean suppressed,
    SuppressionInfo suppression  // null unless suppressed
) {
    /**
     * Convenience constructor for the common, un-suppressed case. Keeps every existing
     * 8-arg call site (and reporter test) working unchanged.
     */
    public Finding(
        Coordinates coordinates,
        String fromVersion,
        String toVersion,
        int riskScore,
        Severity riskLevel,
        List<RuleResult> signals,
        boolean isUnresolved,
        boolean hasUnknownMetadata
    ) {
        this(coordinates, fromVersion, toVersion, riskScore, riskLevel, signals,
                isUnresolved, hasUnknownMetadata, false, null);
    }

    public static Finding unresolved(Coordinates coordinates) {
        return new Finding(coordinates, null, coordinates.version(), 0, null, List.of(), true, false);
    }

    /** Returns a copy of this finding marked suppressed by the given whitelist match. */
    public Finding withSuppression(SuppressionInfo info) {
        return new Finding(coordinates, fromVersion, toVersion, riskScore, riskLevel,
                signals, isUnresolved, hasUnknownMetadata, true, info);
    }
}

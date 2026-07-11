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
 * When {@code hasUnexpandedSubtree} is true the dependency itself resolved and was scored,
 * but its descriptor could not be read, so its own (transitive) dependencies were never
 * walked — that subtree is unscanned and must be surfaced, never treated as clean.
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
    boolean hasUnexpandedSubtree
) {
    public Finding(Coordinates coordinates, String fromVersion, String toVersion, int riskScore,
            Severity riskLevel, List<RuleResult> signals, boolean isUnresolved, boolean hasUnknownMetadata) {
        this(coordinates, fromVersion, toVersion, riskScore, riskLevel, signals,
                isUnresolved, hasUnknownMetadata, false);
    }

    public static Finding unresolved(Coordinates coordinates) {
        return new Finding(coordinates, null, coordinates.version(), 0, null, List.of(), true, false);
    }

    /** Copy of this finding marked as having an unscanned transitive subtree. */
    public Finding withUnexpandedSubtree() {
        return new Finding(coordinates, fromVersion, toVersion, riskScore, riskLevel, signals,
                isUnresolved, hasUnknownMetadata, true);
    }
}

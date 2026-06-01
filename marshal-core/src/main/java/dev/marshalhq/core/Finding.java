package dev.marshalhq.core;

import java.util.List;

/**
 * Fully evaluated result for one dependency. Used by all reporters (terminal, JSON, markdown)
 * and by the diff command.
 *
 * When {@code isUnresolved} is true the version could not be determined; {@code riskLevel}
 * is null and the dep must never be silently dropped or counted as GREEN.
 *
 * When {@code hasUnknownMetadata} is true at least one metadata field could not be fetched
 * (e.g. signature status is UNKNOWN); results may be partial.
 */
public record Finding(
    Coordinates coordinates,
    String fromVersion,          // previous version; null = first-ever release
    String toVersion,            // version from the project POM
    int riskScore,               // 0–100; 0 when isUnresolved
    Severity riskLevel,          // null when isUnresolved
    List<RuleResult> signals,
    boolean isUnresolved,
    boolean hasUnknownMetadata
) {
    public static Finding unresolved(Coordinates coords) {
        return new Finding(coords, null, coords.version(), 0, null, List.of(), true, false);
    }
}

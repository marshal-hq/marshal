package dev.marshalhq.core;

/**
 * The audit record attached to a suppressed {@link Finding}: which list matched, plus
 * the suppression metadata. An auditor needs to be able to see "the engine flagged X,
 * we suppressed it because of whitelist entry Y, reason Z, expires W". Fields are plain
 * strings so they drop straight into the JSON output without pulling in the whitelist
 * package.
 */
public record SuppressionInfo(
        String matchedList,   // "marshal" or "user"
        String reason,
        String expires,       // ISO date, or null for non-expiring (Marshal) entries
        String addedBy        // null when not recorded
) {
}

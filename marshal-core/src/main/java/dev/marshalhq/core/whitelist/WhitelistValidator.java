package dev.marshalhq.core.whitelist;

import java.util.Set;

/**
 * Checks the granularity rule at load time: every entry must be a full
 * {@code groupId:artifactId:version} coordinate pinned to one version, with no
 * wildcards. A new version is exactly where a compromise lands, so trusting a whole
 * groupId or artifactId would blind Marshal to the thing it watches for. This holds
 * for both the user list and the Marshal list.
 */
public final class WhitelistValidator {

    // Common placeholder reasons that defeat the audit purpose of the field.
    private static final Set<String> PLACEHOLDER_REASONS =
            Set.of("todo", "tbd", "xxx", "n/a", "na", "none", "-", ".", "fixme", "placeholder");

    private WhitelistValidator() {
    }

    /**
     * Rejects anything that is not a fully version-pinned GAV coordinate. Throws
     * {@link WhitelistException} on the first violation so the offending line is named.
     */
    public static void requireValidGav(String gav) {
        if (gav == null || gav.isBlank()) {
            throw new WhitelistException("Whitelist entry has a blank gav coordinate.");
        }
        if (gav.indexOf('*') >= 0) {
            throw new WhitelistException(
                    "Whitelist entry '" + gav + "' uses a wildcard. Wildcards are forbidden — "
                            + "pin the full groupId:artifactId:version.");
        }
        String[] parts = parseGAVParts(gav);
        for (String part : parts) {
            if (part.isBlank()) {
                throw new WhitelistException(
                        "Whitelist entry '" + gav + "' has an empty segment. Use groupId:artifactId:version.");
            }
        }
    }

    private static String[] parseGAVParts(String gav) {
        String[] parts = gav.split(":", -1);
        if (parts.length != 3) {
            if (parts.length < 3) {
                throw new WhitelistException(
                        "Whitelist entry '" + gav + "' is not version-pinned. "
                                + "Use groupId:artifactId:version (a missing version means all versions, which is forbidden).");
            }
            throw new WhitelistException(
                    "Whitelist entry '" + gav + "' has too many segments. Use groupId:artifactId:version.");
        }
        return parts;
    }

    /**
     * Requires a meaningful reason: non-blank and not a known placeholder. Empty or
     * placeholder reasons defeat the audit justification, so they are a hard
     * {@link WhitelistException} naming the offending entry.
     */
    public static void requireMeaningfulReason(String reason, String gav) {
        boolean meaningful = reason != null && !reason.isBlank()
                && !PLACEHOLDER_REASONS.contains(reason.trim().toLowerCase());
        if (!meaningful) {
            throw new WhitelistException(
                    "Whitelist entry '" + gav + "' is missing a meaningful 'reason'. "
                            + "Empty or placeholder reasons are rejected.");
        }
    }
}

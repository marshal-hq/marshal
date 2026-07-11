package dev.marshalhq.core.whitelist;

import java.time.LocalDate;

/**
 * One vetted, version-pinned coordinate. {@code gav} and {@code reason} are always
 * present. {@code addedBy}/{@code addedOn} are optional (recommended for user entries);
 * {@code expires} is required for user entries and absent for Marshal-maintained
 * entries, which never lapse.
 */
public record WhitelistEntry(
        String gav,
        String reason,
        String addedBy,
        LocalDate addedOn,
        LocalDate expires
) {

    /**
     * An entry is expired when it carries an {@code expires} date that is strictly
     * before {@code asOf}. The expiry date itself is still active; suppression stops
     * the day after.
     */
    public boolean isExpired(LocalDate asOf) {
        return expires != null && asOf.isAfter(expires);
    }
}

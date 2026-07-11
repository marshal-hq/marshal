package dev.marshalhq.core.whitelist;

import dev.marshalhq.core.SuppressionInfo;

import java.time.LocalDate;
import java.util.Optional;

/**
 * The two active whitelists for a scan. The Marshal-maintained list is consulted
 * first, then the user list. A finding is suppressed when its GAV is present and
 * unexpired in either one, and we record which list matched for the audit trail.
 */
public final class Whitelists {

    private final Whitelist marshal;
    private final Whitelist user;

    public Whitelists(Whitelist marshal, Whitelist user) {
        this.marshal = marshal;
        this.user = user;
    }

    public static Whitelists empty() {
        return new Whitelists(Whitelist.empty(WhitelistSource.MARSHAL), Whitelist.empty(WhitelistSource.USER));
    }

    /**
     * Whether the given GAV is suppressed as of {@code asOf}, and by which list. The
     * Marshal list wins a tie: a curated baseline is the broader trust claim and keeps
     * the audit record pointing at the authoritative source.
     */
    public Optional<SuppressionInfo> decide(String gav, LocalDate asOf) {
        Optional<WhitelistEntry> m = marshal.find(gav, asOf);
        if (m.isPresent()) {
            return Optional.of(toInfo(WhitelistSource.MARSHAL, m.get()));
        }
        Optional<WhitelistEntry> u = user.find(gav, asOf);
        return u.map(whitelistEntry -> toInfo(WhitelistSource.USER, whitelistEntry));
    }

    public Whitelist marshal() {
        return marshal;
    }

    public Whitelist user() {
        return user;
    }

    private static SuppressionInfo toInfo(WhitelistSource source, WhitelistEntry entry) {
        return new SuppressionInfo(
                source.label(),
                entry.reason(),
                entry.expires() != null ? entry.expires().toString() : null,
                entry.addedBy());
    }
}

package dev.marshalhq.registry;

import dev.marshalhq.core.whitelist.Whitelist;
import dev.marshalhq.core.whitelist.WhitelistSource;

import java.time.Instant;

/**
 * Picks the active whitelist by newest {@code updated} timestamp among the trusted
 * candidates: embedded baseline, last-cached remote, fresh remote. Pure and
 * order-stable. Pass the embedded baseline first so it wins exact ties; that way a
 * validly-signed copy with the same timestamp can't roll the client backward.
 */
final class ActiveWhitelistSelector {

    private ActiveWhitelistSelector() {
    }

    static Whitelist newest(Whitelist... candidates) {
        Whitelist best = null;
        for (Whitelist c : candidates) {
            if (c == null) {
                continue;
            }
            if (best == null || isStrictlyNewer(c.updated(), best.updated())) {
                best = c;
            }
        }
        return best != null ? best : Whitelist.empty(WhitelistSource.MARSHAL);
    }

    private static boolean isStrictlyNewer(Instant candidate, Instant incumbent) {
        Instant c = candidate != null ? candidate : Instant.MIN;
        Instant i = incumbent != null ? incumbent : Instant.MIN;
        return c.isAfter(i);
    }
}

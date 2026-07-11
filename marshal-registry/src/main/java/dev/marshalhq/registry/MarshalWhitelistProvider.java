package dev.marshalhq.registry;

import dev.marshalhq.core.whitelist.Whitelist;
import dev.marshalhq.core.whitelist.WhitelistLoader;

import java.nio.file.Path;

/**
 * Resolves the active Marshal-maintained whitelist for a scan. The embedded baseline
 * frozen in the jar is the offline floor that is always there. When refresh is on, the
 * signed remote copy and its cached form join in, and whichever has the newest
 * {@code updated} timestamp wins.
 * <p>
 * A failed refresh never fails the scan. {@link RemoteWhitelistClient} has the fallback
 * chain.
 */
public final class MarshalWhitelistProvider {

    private MarshalWhitelistProvider() {
    }

    /**
     * @param frozen    when true, use the embedded baseline only, with no fetch and no
     *                  cache. This backs {@code --frozen-whitelist} and the Action's
     *                  {@code update-whitelist: false}.
     * @param cachePath the SQLite cache location (may be null to skip caching).
     */
    public static Whitelist active(boolean frozen, Path cachePath) {
        Whitelist embedded = WhitelistLoader.loadEmbeddedBaseline();
        if (frozen || !RemoteWhitelistClient.isConfigured()) {
            return embedded;
        }
        return new RemoteWhitelistClient(cachePath).refreshed(embedded);
    }
}

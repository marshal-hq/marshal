package dev.marshalhq.registry;

import dev.marshalhq.core.whitelist.Whitelist;
import dev.marshalhq.core.whitelist.WhitelistLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Refreshes the Marshal-maintained whitelist from the signed remote copy. Inside the
 * 24h cache TTL the cached copy is used and nothing is fetched. Past it, the client
 * pulls the live file and its detached signature, checks the signature against the
 * embedded public key, and only then caches and considers the copy. A network or
 * verification failure never fails the scan: the client drops back to the last verified
 * cache, then the embedded baseline.
 * <p>
 * The active source is whichever of {embedded, cached, fresh} carries the newest
 * {@code updated} timestamp. {@link ActiveWhitelistSelector} does that part.
 */
public final class RemoteWhitelistClient {

    private static final Logger log = LoggerFactory.getLogger(RemoteWhitelistClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String URL_PROPERTY = "marshal.whitelist.url";
    private static final String URL_ENV = "MARSHAL_WHITELIST_URL";
    private static final String PUBLIC_KEY_RESOURCE = "marshal-release-pubkey.asc";

    private final HttpClient http;
    private final MetadataCache cache;     // nullable: no caching when absent
    private final String url;              // nullable: remote disabled when absent
    private final byte[] publicKey;        // nullable: remote disabled without a verification key
    private final WhitelistSignatureVerifier verifier;

    public RemoteWhitelistClient(Path cachePath) {
        this(defaultHttp(), openCache(cachePath), configuredUrl(), embeddedPublicKey(),
                new WhitelistSignatureVerifier());
    }

    RemoteWhitelistClient(HttpClient http, MetadataCache cache, String url, byte[] publicKey,
            WhitelistSignatureVerifier verifier) {
        this.http = http;
        this.cache = cache;
        this.url = url;
        this.publicKey = publicKey;
        this.verifier = verifier;
    }

    /** Whether a remote whitelist URL is configured at all. */
    public static boolean isConfigured() {
        return configuredUrl() != null;
    }

    /**
     * Returns the active whitelist given the embedded baseline as the floor. Never
     * throws and never returns null.
     */
    public Whitelist refreshed(Whitelist embedded) {
        MetadataCache.CachedWhitelist cachedRow = cache != null ? cache.getWhitelist() : null;
        Whitelist cached = cachedRow != null ? safeParse(cachedRow.yaml()) : null;

        Whitelist fresh = null;
        boolean withinTtl = cachedRow != null && cachedRow.isFresh();
        if (url != null && publicKey != null && !withinTtl) {
            fresh = fetchVerifyCache();
        }

        // Embedded is passed first so it wins exact-timestamp ties (rollback floor).
        return ActiveWhitelistSelector.newest(embedded, cached, fresh);
    }

    // ── fetch + verify + cache ──────────────────────────────────────────────────────

    private Whitelist fetchVerifyCache() {
        try {
            byte[] doc = get(url);
            byte[] sig = get(url + ".asc");
            if (doc == null || sig == null) {
                return null;
            }
            if (!verifier.verify(doc, sig, new ByteArrayInputStream(publicKey))) {
                // Treated as a potential attack: discard and fall back. Never use an
                // unverified or failed-verification remote copy.
                log.warn("Remote whitelist signature did not verify — discarding fetched copy");
                return null;
            }
            String yaml = new String(doc, StandardCharsets.UTF_8);
            Whitelist wl = WhitelistLoader.parseMarshal(yaml);
            if (cache != null && wl.updated() != null) {
                cache.putWhitelist(yaml, wl.updated().toEpochMilli());
            }
            return wl;
        }
        catch (Exception e) {
            log.debug("Remote whitelist refresh failed, falling back: {}", e.getMessage());
            return null;
        }
    }

    private byte[] get(String target) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(target)).timeout(TIMEOUT).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.debug("Remote whitelist fetch of {} returned HTTP {}", target, resp.statusCode());
            return null;
        }
        return resp.body().getBytes(StandardCharsets.UTF_8);
    }

    private static Whitelist safeParse(String yaml) {
        try {
            return WhitelistLoader.parseMarshal(yaml);
        }
        catch (Exception e) {
            log.warn("Cached whitelist could not be parsed, ignoring it: {}", e.getMessage());
            return null;
        }
    }

    // ── configuration ───────────────────────────────────────────────────────────────

    static String configuredUrl() {
        String prop = System.getProperty(URL_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String env = System.getenv(URL_ENV);
        return (env != null && !env.isBlank()) ? env.trim() : null;
    }

    private static byte[] embeddedPublicKey() {
        try (InputStream in = RemoteWhitelistClient.class.getClassLoader()
                .getResourceAsStream(PUBLIC_KEY_RESOURCE)) {
            return in != null ? in.readAllBytes() : null;
        }
        catch (IOException e) {
            return null;
        }
    }

    private static MetadataCache openCache(Path cachePath) {
        if (cachePath == null) {
            return null;
        }
        try {
            return new MetadataCache(cachePath);
        }
        catch (Exception e) {
            log.warn("Could not open whitelist cache: {}", e.getMessage());
            return null;
        }
    }

    private static HttpClient defaultHttp() {
        return HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }
}

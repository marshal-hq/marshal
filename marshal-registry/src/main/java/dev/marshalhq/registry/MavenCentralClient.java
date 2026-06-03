package dev.marshalhq.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marshalhq.core.Coordinates;
import dev.marshalhq.core.SignatureStatus;
import dev.marshalhq.core.VersionMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

public class MavenCentralClient {
    private static final Logger log = LoggerFactory.getLogger(MavenCentralClient.class);
    private static final String SEARCH_BASE = "https://search.maven.org/solrsearch/select";
    private static final String REPO_BASE = "https://repo1.maven.org/maven2";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final MetadataCache cache;

    public MavenCentralClient() {
        this(defaultHttpClient(), null);
    }

    public MavenCentralClient(MetadataCache cache) {
        this(defaultHttpClient(), cache);
    }

    /** Package-private: used in tests to inject a mock HttpClient. */
    MavenCentralClient(HttpClient http) {
        this(http, null);
    }

    /** Package-private: used in tests to inject both a mock HttpClient and a cache. */
    MavenCentralClient(HttpClient http, MetadataCache cache) {
        this.http = http;
        this.mapper = new ObjectMapper();
        this.cache = cache;
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    }

    /**
     * Returns version strings for the given artifact, most recent first.
     * Result is served from the cache when available; fetched and cached on miss.
     */
    public List<String> getVersionHistory(String groupId, String artifactId) {
        if (cache != null) {
            List<String> cached = cache.getVersionHistory(groupId, artifactId);
            if (cached != null) return cached;
        }

        String url = SEARCH_BASE + "?q=g:%22" + groupId + "%22+AND+a:%22" + artifactId +
            "%22&core=gav&rows=20&wt=json";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "marshal-cli/0.1.0")
                .timeout(REQUEST_TIMEOUT)
                .GET().build();
            JsonNode root = mapper.readTree(sendWithRetry(req).body());
            List<String> versions = new ArrayList<>();
            for (JsonNode doc : root.path("response").path("docs")) {
                versions.add(doc.path("v").asText());
            }
            if (cache != null) cache.putVersionHistory(groupId, artifactId, versions);
            return versions;
        } catch (Exception e) {
            log.warn("Failed to fetch version history for {}:{} — {}", groupId, artifactId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Fetches full metadata for a specific version. Never returns null.
     * Served from cache when available; on miss, fetches and caches the result.
     * On partial failure: signatureStatus=UNKNOWN (not ABSENT), depCount=-1 (not 0).
     * Rules must abstain on these sentinels to avoid false findings from network errors.
     */
    public VersionMetadata fetchMetadata(Coordinates coords) {
        if (cache != null) {
            VersionMetadata cached = cache.get(coords);
            if (cached != null) return cached;
        }

        Instant publishedAt = fetchPublishedAt(coords);

        // --- Signature check (retry on 429) ---
        SignatureStatus sigStatus = SignatureStatus.UNKNOWN;
        String sigKeyId = null;
        String ascUrl = REPO_BASE + "/" + pomBasePath(coords) +
            coords.artifactId() + "-" + coords.version() + ".jar.asc";
        try {
            HttpRequest sigReq = HttpRequest.newBuilder()
                .uri(URI.create(ascUrl))
                .header("User-Agent", "marshal-cli/0.1.0")
                .timeout(REQUEST_TIMEOUT)
                .GET().build();
            HttpResponse<String> sigResp = sendWithRetry(sigReq);
            if (sigResp.statusCode() == 200) {
                sigKeyId = extractKeyId(sigResp.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                sigStatus = SignatureStatus.PRESENT;
            } else if (sigResp.statusCode() == 404) {
                sigStatus = SignatureStatus.ABSENT;
            }
            // other statuses (429 exhausted, 5xx) → UNKNOWN (default)
        } catch (Exception e) {
            log.debug("Sig fetch failed for {} — {}", coords.toGav(), e.getMessage());
        }

        // --- POM fetch (retry on 429) ---
        int depCount = -1;  // -1 = POM fetch failed; rules abstain rather than treat as 0
        String repoUrl = null;
        String pomUrl = REPO_BASE + "/" + pomBasePath(coords) +
            coords.artifactId() + "-" + coords.version() + ".pom";
        try {
            HttpRequest pomReq = HttpRequest.newBuilder()
                .uri(URI.create(pomUrl))
                .header("User-Agent", "marshal-cli/0.1.0")
                .timeout(REQUEST_TIMEOUT)
                .GET().build();
            HttpResponse<String> pomResp = sendWithRetry(pomReq);
            if (pomResp.statusCode() == 200) {
                org.apache.maven.model.Model pom = new org.apache.maven.model.io.xpp3.MavenXpp3Reader()
                    .read(new StringReader(pomResp.body()));
                depCount = pom.getDependencies().size();
                if (pom.getScm() != null) repoUrl = pom.getScm().getUrl();
            }
            // non-200 (including 404, 429 exhausted) → depCount stays -1
        } catch (Exception e) {
            log.debug("POM fetch failed for {} — {}", coords.toGav(), e.getMessage());
        }

        VersionMetadata result = new VersionMetadata(
            coords,
            null,           // publisherEmail: not exposed by Maven Central API
            sigKeyId,       // gpgKeyFingerprint: from .asc; null when ABSENT or UNKNOWN
            sigStatus,
            List.of(),      // installScripts: Maven has no install hooks
            depCount,       // from POM; -1 if fetch failed
            repoUrl,        // from POM <scm>; null if fetch failed or no <scm>
            publishedAt != null ? publishedAt : Instant.EPOCH,
            false           // isYanked: Maven Central is immutable
        );
        // Only cache complete results. Partial/failed fetches (UNKNOWN sig or
        // depCount == -1) MUST NOT be memoised — otherwise a transient 429 sticks
        // for the full 24h TTL and rules abstain forever on that GAV.
        boolean isComplete = sigStatus != SignatureStatus.UNKNOWN && depCount != -1;
        if (cache != null && isComplete) {
            cache.put(coords, result);
        }
        return result;
    }

    private Instant fetchPublishedAt(Coordinates coords) {
        String url = SEARCH_BASE + "?q=g:%22" + coords.groupId() + "%22+AND+a:%22" +
            coords.artifactId() + "%22+AND+v:%22" + coords.version() +
            "%22&core=gav&rows=1&wt=json";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "marshal-cli/0.1.0")
                .timeout(REQUEST_TIMEOUT)
                .GET().build();
            JsonNode doc = mapper.readTree(sendWithRetry(req).body())
                .path("response").path("docs").path(0);
            long ts = doc.path("timestamp").asLong(0);
            return ts > 0 ? Instant.ofEpochMilli(ts) : null;
        } catch (Exception e) {
            log.debug("Could not fetch timestamp for {} — {}", coords.toGav(), e.getMessage());
            return null;
        }
    }

    private String pomBasePath(Coordinates coords) {
        return coords.groupId().replace('.', '/') + "/" +
            coords.artifactId() + "/" + coords.version() + "/";
    }

    /** Retries on HTTP 429 with exponential backoff: 500 ms then 1000 ms. */
    private HttpResponse<String> sendWithRetry(HttpRequest req) throws IOException, InterruptedException {
        int attempt = 0;
        while (true) {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 429 && attempt < 2) {
                Thread.sleep(500L << attempt);
                attempt++;
            } else {
                return resp;
            }
        }
    }

    /**
     * Extracts the 64-bit Issuer Key ID from an ASCII-armored OpenPGP signature.
     * Uses BouncyCastle to handle v4/v6 formats, hashed/unhashed subpackets, and
     * all subpacket length encodings correctly.
     * Returns a 16-char uppercase hex string, or null if parsing fails.
     */
    static String extractKeyId(byte[] armoredSigBytes) {
        if (armoredSigBytes == null || armoredSigBytes.length == 0) return null;
        try (java.io.InputStream in = org.bouncycastle.openpgp.PGPUtil.getDecoderStream(
                new java.io.ByteArrayInputStream(armoredSigBytes))) {
            org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory factory =
                new org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory(in);
            Object obj = factory.nextObject();
            if (obj instanceof org.bouncycastle.openpgp.PGPSignatureList sigs && !sigs.isEmpty()) {
                return String.format("%016X", sigs.get(0).getKeyID());
            }
        } catch (Exception e) {
            log.warn("Failed to parse GPG signature: {}", e.getMessage());
        }
        return null;
    }
}

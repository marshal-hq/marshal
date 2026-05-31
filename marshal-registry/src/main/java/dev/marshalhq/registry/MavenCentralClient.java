package dev.marshalhq.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marshalhq.core.Coordinates;
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
import java.util.Base64;
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

    public MavenCentralClient() {
        this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Returns version strings for the given artifact, most recent first.
     * Block 1 scan coordinator uses this to resolve previous/history versions.
     */
    public List<String> getVersionHistory(String groupId, String artifactId) {
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
            return versions;
        } catch (Exception e) {
            log.warn("Failed to fetch version history for {}:{} — {}", groupId, artifactId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Fetches full metadata for a specific version. Never returns null:
     * partial failures produce stubs with hasSig=false, depCount=0, etc.
     * A failed fetch returns a stub rather than null so callers never NPE.
     */
    public VersionMetadata fetchMetadata(Coordinates coords) {
        Instant publishedAt = fetchPublishedAt(coords);
        String sigKeyId = fetchSigKeyId(coords);
        boolean hasSig = sigKeyId != null;

        org.apache.maven.model.Model pom = fetchPomModel(coords);
        int depCount = 0;
        String repoUrl = null;
        if (pom != null) {
            depCount = pom.getDependencies().size();
            if (pom.getScm() != null) repoUrl = pom.getScm().getUrl();
        }

        return new VersionMetadata(
            coords,
            null,       // publisherEmail: not exposed by Maven Central API
            sigKeyId,   // gpgKeyFingerprint: extracted from .asc signature file
            hasSig,
            List.of(),  // installScripts: Maven has no install hooks
            depCount,   // from POM dependency count
            repoUrl,    // from POM <scm><url>
            publishedAt != null ? publishedAt : Instant.EPOCH,
            false       // isYanked: Maven Central is immutable; rule inert on this ecosystem
        );
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

    /**
     * Fetches the .asc signature file and extracts the OpenPGP key ID.
     * Returns the 16-hex-char key ID string if the signature exists, null otherwise.
     * The key ID is used as gpgKeyFingerprint for NewMaintainerRule fingerprint detection.
     */
    private String fetchSigKeyId(Coordinates coords) {
        String url = REPO_BASE + "/" + pomBasePath(coords) +
            coords.artifactId() + "-" + coords.version() + ".jar.asc";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "marshal-cli/0.1.0")
                .timeout(REQUEST_TIMEOUT)
                .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            return extractKeyId(decodeArmor(resp.body()));
        } catch (Exception e) {
            return null;
        }
    }

    private org.apache.maven.model.Model fetchPomModel(Coordinates coords) {
        String url = REPO_BASE + "/" + pomBasePath(coords) +
            coords.artifactId() + "-" + coords.version() + ".pom";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "marshal-cli/0.1.0")
                .timeout(REQUEST_TIMEOUT)
                .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            return new org.apache.maven.model.io.xpp3.MavenXpp3Reader()
                .read(new StringReader(resp.body()));
        } catch (Exception e) {
            log.debug("Could not fetch/parse POM for {} — {}", coords.toGav(), e.getMessage());
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
     * Strips ASCII armor headers/checksum and base64-decodes the OpenPGP binary body.
     */
    static byte[] decodeArmor(String armored) {
        try {
            StringBuilder b64 = new StringBuilder();
            boolean inBody = false;
            for (String line : armored.split("\n")) {
                String t = line.trim();
                if (t.startsWith("-----BEGIN")) { inBody = false; continue; }
                if (t.startsWith("-----END")) break;
                if (t.isEmpty() && !inBody) { inBody = true; continue; }
                if (!inBody) continue;
                if (t.startsWith("=")) break; // CRC24 checksum line
                b64.append(t);
            }
            return Base64.getDecoder().decode(b64.toString());
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /**
     * Extracts the 8-byte Issuer Key ID (subpacket type 0x10) from an OpenPGP v4
     * Signature packet binary (RFC 4880 §5.2.3). Returns a 16-char hex string or null.
     */
    static String extractKeyId(byte[] sig) {
        if (sig == null || sig.length < 10) return null;
        try {
            int pos = 0;
            // Advance past the packet header and its length field
            int header = sig[pos++] & 0xFF;
            boolean newFmt = (header & 0x40) != 0;
            if (newFmt) {
                int first = sig[pos++] & 0xFF;
                if (first >= 192 && first < 224) pos++;      // 2-byte length
                else if (first == 255) pos += 4;             // 5-byte length
            } else {
                switch (header & 0x03) {                     // old-format length type
                    case 0 -> pos++;
                    case 1 -> pos += 2;
                    case 2 -> pos += 4;
                }
            }
            if (pos >= sig.length || (sig[pos] & 0xFF) != 4) return null; // v4 only
            pos += 4; // version + sigType + pubKeyAlgo + hashAlgo
            if (pos + 2 > sig.length) return null;
            int hashedLen = ((sig[pos] & 0xFF) << 8) | (sig[pos + 1] & 0xFF);
            pos += 2 + hashedLen;
            if (pos + 2 > sig.length) return null;
            int unhashedLen = ((sig[pos] & 0xFF) << 8) | (sig[pos + 1] & 0xFF);
            pos += 2;
            int end = Math.min(pos + unhashedLen, sig.length);
            while (pos < end) {
                int subLen = sig[pos++] & 0xFF; // length includes type byte, not itself
                if (subLen == 0 || pos + subLen > end) break;
                if ((sig[pos] & 0xFF) == 0x10 && subLen == 9) { // Issuer Key ID subpacket
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i <= 8; i++) sb.append(String.format("%02X", sig[pos + i] & 0xFF));
                    return sb.toString();
                }
                pos += subLen;
            }
        } catch (Exception ignored) {
            // malformed signature packet — caller treats as unsigned
        }
        return null;
    }
}

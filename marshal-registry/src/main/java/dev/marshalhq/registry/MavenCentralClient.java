package dev.marshalhq.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marshalhq.core.Coordinates;
import dev.marshalhq.core.VersionMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MavenCentralClient {
    private static final Logger log = LoggerFactory.getLogger(MavenCentralClient.class);
    private static final String BASE = "https://search.maven.org/solrsearch/select";
    private final HttpClient http;
    private final ObjectMapper mapper;

    public MavenCentralClient() {
        this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .build();
        this.mapper = new ObjectMapper();
    }

    public List<String> getVersionHistory(String groupId, String artifactId) {
        String url = BASE + "?q=g:%22" + groupId + "%22+AND+a:%22" + artifactId +
            "%22&core=gav&rows=20&wt=json";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "marshal-cli/0.1.0")
                .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            JsonNode docs = root.path("response").path("docs");
            List<String> versions = new ArrayList<>();
            for (JsonNode doc : docs) {
                versions.add(doc.path("v").asText());
            }
            return versions;
        } catch (Exception e) {
            log.warn("Failed to fetch version history for {}:{} — {}", groupId, artifactId, e.getMessage());
            return Collections.emptyList();
        }
    }

    public VersionMetadata fetchMetadata(Coordinates coords) {
        // For MVP: fetch what Maven Central search API exposes
        // Full POM parsing comes in v0.2
        String url = BASE + "?q=g:%22" + coords.groupId() + "%22+AND+a:%22" +
            coords.artifactId() + "%22+AND+v:%22" + coords.version() +
            "%22&core=gav&rows=1&wt=json";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "marshal-cli/0.1.0")
                .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            JsonNode doc = root.path("response").path("docs").path(0);

            boolean hasSig = checkSignatureExists(coords);

            return new VersionMetadata(
                coords,
                null,          // publisherEmail: not available via search API
                null,          // gpgKeyFingerprint: not available via search API
                hasSig,
                List.of(),     // installScripts: Maven has no install hooks
                0,             // dependencyCount: requires POM parsing (v0.2)
                null,          // repoUrl: requires POM parsing (v0.2)
                Instant.ofEpochMilli(doc.path("timestamp").asLong(0)),
                false          // isYanked: Maven Central doesn't yank
            );
        } catch (Exception e) {
            log.warn("Failed to fetch metadata for {} — {}", coords.toGav(), e.getMessage());
            return null;
        }
    }

    private boolean checkSignatureExists(Coordinates coords) {
        String path = coords.groupId().replace('.', '/') + "/" +
            coords.artifactId() + "/" + coords.version() + "/" +
            coords.artifactId() + "-" + coords.version() + ".jar.asc";
        String url = "https://repo1.maven.org/maven2/" + path;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", "marshal-cli/0.1.0")
                .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}

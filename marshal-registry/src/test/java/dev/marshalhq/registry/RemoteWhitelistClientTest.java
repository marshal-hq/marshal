package dev.marshalhq.registry;

import dev.marshalhq.core.whitelist.Whitelist;
import dev.marshalhq.core.whitelist.WhitelistLoader;
import dev.marshalhq.core.whitelist.WhitelistSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RemoteWhitelistClientTest {

    @Mock HttpClient http;

    private static PgpTestSupport.Keys KEYS;
    private static final String URL = "https://marshalhq.dev/whitelist/marshal-whitelist.yml";

    private static final String EMBEDDED_YAML = """
            version: 1
            updated: "2026-06-01T00:00:00Z"
            entries:
              - gav: "org.springframework:spring-core:6.1.5"
                reason: "embedded baseline"
            """;

    private static final String REMOTE_YAML = """
            version: 1
            updated: "2026-06-23T00:00:00Z"
            entries:
              - gav: "com.fasterxml.jackson.core:jackson-databind:2.17.0"
                reason: "fresh remote"
            """;

    @BeforeAll
    static void keys() throws Exception {
        KEYS = PgpTestSupport.generateKeys();
    }

    private Whitelist embedded() {
        return WhitelistLoader.parseMarshal(EMBEDDED_YAML);
    }

    private RemoteWhitelistClient client(MetadataCache cache) {
        return new RemoteWhitelistClient(http, cache, URL, KEYS.publicKeyRing(), new WhitelistSignatureVerifier());
    }

    /** Routes the doc URL and the {@code .asc} URL to their respective bodies. */
    private void stubRemote(String docYaml, String sigBody) throws Exception {
        doAnswer(inv -> {
            HttpRequest req = inv.getArgument(0);
            String uri = req.uri().toString();
            if (uri.endsWith(".asc")) {
                return resp(200, sigBody);
            }
            return resp(200, docYaml);
        }).when(http).send(any(HttpRequest.class), any());
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> resp(int status, String body) {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        return r;
    }

    // ── happy path ──────────────────────────────────────────────────────────────────

    @Test
    void verifiedFresherRemoteSupersedesEmbeddedAndIsCached() throws Exception {
        MetadataCache cache = new MetadataCache("jdbc:sqlite::memory:");
        byte[] sig = PgpTestSupport.sign(REMOTE_YAML.getBytes(java.nio.charset.StandardCharsets.UTF_8), KEYS);
        stubRemote(REMOTE_YAML, new String(sig, java.nio.charset.StandardCharsets.UTF_8));

        Whitelist active = client(cache).refreshed(embedded());

        assertThat(active.updated()).isEqualTo(Instant.parse("2026-06-23T00:00:00Z"));
        assertThat(active.find("com.fasterxml.jackson.core:jackson-databind:2.17.0",
                java.time.LocalDate.parse("2026-06-23"))).isPresent();
        // It was written to the single-document cache.
        assertThat(cache.getWhitelist()).isNotNull();
        assertThat(cache.getWhitelist().updatedEpochMs())
                .isEqualTo(Instant.parse("2026-06-23T00:00:00Z").toEpochMilli());
    }

    // ── integrity ─────────────────────────────────────────────────────────────────

    @Test
    void failedSignatureDiscardsRemoteAndFallsBackToEmbedded() throws Exception {
        MetadataCache cache = new MetadataCache("jdbc:sqlite::memory:");
        // Sign a DIFFERENT payload than what is served → signature will not verify.
        byte[] wrongSig = PgpTestSupport.sign("not the served doc".getBytes(), KEYS);
        stubRemote(REMOTE_YAML, new String(wrongSig, java.nio.charset.StandardCharsets.UTF_8));

        Whitelist active = client(cache).refreshed(embedded());

        // Falls back to embedded; the bad copy is never cached.
        assertThat(active.updated()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(cache.getWhitelist()).isNull();
    }

    @Test
    void networkErrorFallsBackToEmbeddedWithoutFailing() throws Exception {
        MetadataCache cache = new MetadataCache("jdbc:sqlite::memory:");
        doAnswer(inv -> {
            throw new java.io.IOException("connection refused");
        }).when(http).send(any(HttpRequest.class), any());

        Whitelist active = client(cache).refreshed(embedded());
        assertThat(active.updated()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    }

    // ── caching / TTL ────────────────────────────────────────────────────────────────

    @Test
    void withinTtlTheCachedCopyIsUsedAndNoFetchOccurs() throws Exception {
        MetadataCache cache = new MetadataCache("jdbc:sqlite::memory:");
        // Seed a fresh cache entry newer than embedded.
        cache.putWhitelist(REMOTE_YAML, Instant.parse("2026-06-23T00:00:00Z").toEpochMilli());

        Whitelist active = client(cache).refreshed(embedded());

        assertThat(active.updated()).isEqualTo(Instant.parse("2026-06-23T00:00:00Z"));
        verify(http, never()).send(any(), any());   // TTL not expired → no network
    }

    @Test
    void newerEmbeddedBaselineBeatsAStaleCachedCopy() throws Exception {
        MetadataCache cache = new MetadataCache("jdbc:sqlite::memory:");
        // Cached copy is OLDER than embedded (e.g. after a jar upgrade). Even though
        // it is within TTL, newest-timestamp-wins must prefer the embedded baseline.
        cache.putWhitelist("""
                version: 1
                updated: "2026-05-01T00:00:00Z"
                entries: []
                """, Instant.parse("2026-05-01T00:00:00Z").toEpochMilli());

        Whitelist active = client(cache).refreshed(embedded());
        assertThat(active.updated()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(active.source()).isEqualTo(WhitelistSource.MARSHAL);
    }

    // ── disabled paths ───────────────────────────────────────────────────────────────

    @Test
    void noPublicKeyMeansRemoteIsNeverContacted() throws Exception {
        MetadataCache cache = new MetadataCache("jdbc:sqlite::memory:");
        RemoteWhitelistClient noKey =
                new RemoteWhitelistClient(http, cache, URL, null, new WhitelistSignatureVerifier());

        Whitelist active = noKey.refreshed(embedded());

        assertThat(active.updated()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        verify(http, never()).send(any(), any());
    }
}

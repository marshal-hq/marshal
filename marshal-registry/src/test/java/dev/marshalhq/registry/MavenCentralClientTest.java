package dev.marshalhq.registry;

import dev.marshalhq.core.Coordinates;
import dev.marshalhq.core.SignatureStatus;
import dev.marshalhq.core.VersionMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MavenCentralClientTest {

    @Mock HttpClient mockHttp;

    private static final Coordinates COORDS =
        new Coordinates("com.example", "some-lib", "1.0.0");

    private static final String EMPTY_SEARCH_JSON =
        "{\"response\":{\"docs\":[]}}";

    private static final String MINIMAL_POM =
        "<project><dependencies></dependencies></project>";

    private MavenCentralClient client() {
        return new MavenCentralClient(mockHttp);
    }

    @Test
    void sigFetch200_returnsPRESENT() throws Exception {
        HttpResponse<String> search = resp(200, EMPTY_SEARCH_JSON);
        HttpResponse<String> asc    = resp(200, minimalAsc());
        HttpResponse<String> pom    = resp(200, MINIMAL_POM);

        doReturn(search).doReturn(asc).doReturn(pom)
            .when(mockHttp).send(any(HttpRequest.class), any());

        VersionMetadata meta = client().fetchMetadata(COORDS);

        assertThat(meta.signatureStatus()).isEqualTo(SignatureStatus.PRESENT);
    }

    @Test
    void sigFetch404_returnsABSENT() throws Exception {
        HttpResponse<String> search = resp(200, EMPTY_SEARCH_JSON);
        HttpResponse<String> asc404 = resp(404, "");
        HttpResponse<String> pom    = resp(200, MINIMAL_POM);

        doReturn(search).doReturn(asc404).doReturn(pom)
            .when(mockHttp).send(any(HttpRequest.class), any());

        VersionMetadata meta = client().fetchMetadata(COORDS);

        assertThat(meta.signatureStatus()).isEqualTo(SignatureStatus.ABSENT);
        assertThat(meta.gpgKeyFingerprint()).isNull();
    }

    @Test
    void sigFetch429Exhausted_returnsUNKNOWN() throws Exception {
        // search → OK; sig → 429 three times (all retries exhaust); POM → OK
        HttpResponse<String> search  = resp(200, EMPTY_SEARCH_JSON);
        HttpResponse<String> rate1   = resp(429, "");
        HttpResponse<String> rate2   = resp(429, "");
        HttpResponse<String> rate3   = resp(429, "");
        HttpResponse<String> pom     = resp(200, MINIMAL_POM);

        doReturn(search).doReturn(rate1).doReturn(rate2).doReturn(rate3).doReturn(pom)
            .when(mockHttp).send(any(HttpRequest.class), any());

        VersionMetadata meta = client().fetchMetadata(COORDS);

        assertThat(meta.signatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(meta.gpgKeyFingerprint()).isNull();
    }

    @Test
    void sigFetchIOException_returnsUNKNOWN() throws Exception {
        HttpResponse<String> search = resp(200, EMPTY_SEARCH_JSON);
        HttpResponse<String> pom    = resp(200, MINIMAL_POM);

        doReturn(search)
            .doThrow(new IOException("connection timeout"))
            .doReturn(pom)
            .when(mockHttp).send(any(HttpRequest.class), any());

        VersionMetadata meta = client().fetchMetadata(COORDS);

        assertThat(meta.signatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
    }

    @Test
    void pomFetchFailure_setsDepCountMinusOne() throws Exception {
        HttpResponse<String> search  = resp(200, EMPTY_SEARCH_JSON);
        HttpResponse<String> asc404  = resp(404, "");
        HttpResponse<String> pom500  = resp(500, "server error");

        doReturn(search).doReturn(asc404).doReturn(pom500)
            .when(mockHttp).send(any(HttpRequest.class), any());

        VersionMetadata meta = client().fetchMetadata(COORDS);

        assertThat(meta.dependencyCount()).isEqualTo(-1);
        assertThat(meta.repoUrl()).isNull();
    }

    @Test
    void pomFetch200_parsesDependencyCount() throws Exception {
        String pomWithTwoDeps = "<project><dependencies>" +
            "<dependency><groupId>a</groupId><artifactId>b</artifactId><version>1</version></dependency>" +
            "<dependency><groupId>c</groupId><artifactId>d</artifactId><version>2</version></dependency>" +
            "</dependencies></project>";

        HttpResponse<String> search = resp(200, EMPTY_SEARCH_JSON);
        HttpResponse<String> asc404 = resp(404, "");
        HttpResponse<String> pom    = resp(200, pomWithTwoDeps);

        doReturn(search).doReturn(asc404).doReturn(pom)
            .when(mockHttp).send(any(HttpRequest.class), any());

        VersionMetadata meta = client().fetchMetadata(COORDS);

        assertThat(meta.dependencyCount()).isEqualTo(2);
    }

    @Test
    void cacheHit_fetchMetadata_skipsHttp() throws Exception {
        MetadataCache cache = new MetadataCache("jdbc:sqlite::memory:");
        VersionMetadata stored = new dev.marshalhq.core.VersionMetadata(
            COORDS, null, null, SignatureStatus.PRESENT, List.of(), 3,
            "https://github.com/example/lib", Instant.EPOCH, false);
        cache.put(COORDS, stored);

        MavenCentralClient client = new MavenCentralClient(mockHttp, cache);
        VersionMetadata result = client.fetchMetadata(COORDS);

        assertThat(result.signatureStatus()).isEqualTo(SignatureStatus.PRESENT);
        assertThat(result.dependencyCount()).isEqualTo(3);
        verifyNoInteractions(mockHttp);  // no HTTP calls on cache hit
    }

    @Test
    void cacheMiss_fetchMetadata_storesResult() throws Exception {
        MetadataCache cache = new MetadataCache("jdbc:sqlite::memory:");
        HttpResponse<String> search = resp(200, EMPTY_SEARCH_JSON);
        HttpResponse<String> asc404 = resp(404, "");
        HttpResponse<String> pom    = resp(200, MINIMAL_POM);

        doReturn(search).doReturn(asc404).doReturn(pom)
            .when(mockHttp).send(any(HttpRequest.class), any());

        MavenCentralClient client = new MavenCentralClient(mockHttp, cache);
        client.fetchMetadata(COORDS);

        // second call must hit cache, not HTTP
        reset(mockHttp);
        VersionMetadata second = client.fetchMetadata(COORDS);
        assertThat(second.signatureStatus()).isEqualTo(SignatureStatus.ABSENT);
        verifyNoInteractions(mockHttp);
    }

    @Test
    void cacheHit_getVersionHistory_skipsHttp() throws Exception {
        MetadataCache cache = new MetadataCache("jdbc:sqlite::memory:");
        cache.putVersionHistory("com.example", "some-lib", List.of("2.0.0", "1.0.0"));

        MavenCentralClient client = new MavenCentralClient(mockHttp, cache);
        List<String> versions = client.getVersionHistory("com.example", "some-lib");

        assertThat(versions).containsExactly("2.0.0", "1.0.0");
        verifyNoInteractions(mockHttp);
    }

    @Test
    void cacheMiss_getVersionHistory_storesResult() throws Exception {
        MetadataCache cache = new MetadataCache("jdbc:sqlite::memory:");
        String versionJson = "{\"response\":{\"docs\":[{\"v\":\"1.0.0\"},{\"v\":\"0.9.0\"}]}}";
        doReturn(resp(200, versionJson))
            .when(mockHttp).send(any(HttpRequest.class), any());

        MavenCentralClient client = new MavenCentralClient(mockHttp, cache);
        List<String> first = client.getVersionHistory("com.example", "some-lib");
        assertThat(first).containsExactly("1.0.0", "0.9.0");

        reset(mockHttp);
        List<String> second = client.getVersionHistory("com.example", "some-lib");
        assertThat(second).containsExactly("1.0.0", "0.9.0");
        verifyNoInteractions(mockHttp);
    }

    @Test
    void extractKeyId_realCommonsLang3Asc_returnsKnownKeyId() throws Exception {
        byte[] ascBytes;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("commons-lang3-3.14.0.jar.asc")) {
            assertThat(in).as("test resource commons-lang3-3.14.0.jar.asc missing").isNotNull();
            ascBytes = in.readAllBytes();
        }

        String keyId = MavenCentralClient.extractKeyId(ascBytes);

        // Apache Commons signing key — last 8 bytes of fingerprint 2DB4F1EF…A11262CB
        assertThat(keyId).isEqualTo("86FDC7E2A11262CB");
    }

    // ── A2: cache must not store partial/failed results ──────────────────────────

    @Test
    void unknownSigResult_isNotCached() throws Exception {
        MetadataCache cache = new MetadataCache("jdbc:sqlite::memory:");
        HttpResponse<String> search = resp(200, EMPTY_SEARCH_JSON);
        // Sig: 429 three times → UNKNOWN
        HttpResponse<String> r1 = resp(429, "");
        HttpResponse<String> r2 = resp(429, "");
        HttpResponse<String> r3 = resp(429, "");
        HttpResponse<String> pom = resp(200, MINIMAL_POM);

        doReturn(search).doReturn(r1).doReturn(r2).doReturn(r3).doReturn(pom)
            .when(mockHttp).send(any(HttpRequest.class), any());

        new MavenCentralClient(mockHttp, cache).fetchMetadata(COORDS);

        // UNKNOWN sig → result must NOT be in cache (would poison for 24h)
        assertThat(cache.get(COORDS)).isNull();
    }

    @Test
    void pomFetchFailureResult_isNotCached() throws Exception {
        MetadataCache cache = new MetadataCache("jdbc:sqlite::memory:");
        HttpResponse<String> search = resp(200, EMPTY_SEARCH_JSON);
        HttpResponse<String> asc404 = resp(404, "");   // sig ABSENT (success)
        HttpResponse<String> pom500 = resp(500, "");   // POM fails → depCount=-1

        doReturn(search).doReturn(asc404).doReturn(pom500)
            .when(mockHttp).send(any(HttpRequest.class), any());

        new MavenCentralClient(mockHttp, cache).fetchMetadata(COORDS);

        // depCount == -1 → result must NOT be in cache
        assertThat(cache.get(COORDS)).isNull();
    }

    @Test
    void completeResult_isCached() throws Exception {
        MetadataCache cache = new MetadataCache("jdbc:sqlite::memory:");
        HttpResponse<String> search = resp(200, EMPTY_SEARCH_JSON);
        HttpResponse<String> asc404 = resp(404, "");   // sig ABSENT (success)
        HttpResponse<String> pom    = resp(200, MINIMAL_POM);

        doReturn(search).doReturn(asc404).doReturn(pom)
            .when(mockHttp).send(any(HttpRequest.class), any());

        new MavenCentralClient(mockHttp, cache).fetchMetadata(COORDS);

        // ABSENT sig + POM parsed → complete → must be cached
        assertThat(cache.get(COORDS)).isNotNull();
    }

    // ── A5: gpgKeyFingerprint wiring through fetchMetadata ───────────────────────

    @Test
    void fetchMetadata_withRealAscBody_populatesGpgKeyFingerprint() throws Exception {
        byte[] ascBytes;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("commons-lang3-3.14.0.jar.asc")) {
            assertThat(in).isNotNull();
            ascBytes = in.readAllBytes();
        }
        String ascBody = new String(ascBytes, java.nio.charset.StandardCharsets.UTF_8);

        HttpResponse<String> search = resp(200, EMPTY_SEARCH_JSON);
        HttpResponse<String> asc    = resp(200, ascBody);
        HttpResponse<String> pom    = resp(200, MINIMAL_POM);

        doReturn(search).doReturn(asc).doReturn(pom)
            .when(mockHttp).send(any(HttpRequest.class), any());

        VersionMetadata meta = client().fetchMetadata(COORDS);

        assertThat(meta.signatureStatus()).isEqualTo(SignatureStatus.PRESENT);
        assertThat(meta.gpgKeyFingerprint()).isEqualTo("86FDC7E2A11262CB");
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private HttpResponse<String> resp(int status, String body) {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        return r;
    }

    private String minimalAsc() {
        // Minimal ASCII-armored block — any 200 on the .asc URL triggers PRESENT;
        // key extraction may return null (no valid subpacket) but that's fine for the status test.
        return "-----BEGIN PGP SIGNATURE-----\n\nYWJj\n-----END PGP SIGNATURE-----\n";
    }
}

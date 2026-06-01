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

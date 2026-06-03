package dev.marshalhq.cli;

import dev.marshalhq.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlackNotifierTest {

    @Mock HttpClient mockHttp;

    private static final String WEBHOOK = "https://hooks.slack.com/services/TEST";

    // ── fixtures ──────────────────────────────────────────────────────────────────

    private static Finding redFinding() {
        Coordinates c = new Coordinates("com.example", "some-lib", "2.0.0");
        RuleResult sig = new RuleResult(40, Severity.RED, "sig dropped", "SIG-DROPPED");
        return new Finding(c, "1.0.0", "2.0.0", 87, Severity.RED, List.of(sig), false, false);
    }

    private static Finding yellowFinding() {
        Coordinates c = new Coordinates("com.example", "other-lib", "2.0.0");
        return new Finding(c, "1.0.0", "2.0.0", 34, Severity.YELLOW, List.of(), false, false);
    }

    private static Finding greenFinding() {
        Coordinates c = new Coordinates("com.example", "safe-lib", "1.1.0");
        return new Finding(c, null, "1.1.0", 4, Severity.GREEN, List.of(), false, false);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> ok() {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(200);
        when(r.body()).thenReturn("ok");
        return r;
    }

    // ── no webhook → no-op ───────────────────────────────────────────────────────

    @Test
    void blankWebhook_noHttpCall() throws Exception {
        new SlackNotifier(mockHttp).notify(List.of(redFinding()), "", Severity.RED);
        verifyNoInteractions(mockHttp);
    }

    @Test
    void nullWebhook_noHttpCall() throws Exception {
        new SlackNotifier(mockHttp).notify(List.of(redFinding()), null, Severity.RED);
        verifyNoInteractions(mockHttp);
    }

    // ── min-level gating ──────────────────────────────────────────────────────────

    @Test
    void yellowFinding_redMinLevel_noHttpCall() throws Exception {
        new SlackNotifier(mockHttp).notify(List.of(yellowFinding()), WEBHOOK, Severity.RED);
        verifyNoInteractions(mockHttp);
    }

    @Test
    void greenFinding_orangeMinLevel_noHttpCall() throws Exception {
        new SlackNotifier(mockHttp).notify(List.of(greenFinding()), WEBHOOK, Severity.ORANGE);
        verifyNoInteractions(mockHttp);
    }

    @Test
    void noFindings_noHttpCall() throws Exception {
        new SlackNotifier(mockHttp).notify(List.of(), WEBHOOK, Severity.RED);
        verifyNoInteractions(mockHttp);
    }

    // ── qualifying finding → POST made ────────────────────────────────────────────

    @Test
    void redFinding_redMinLevel_postsToWebhook() throws Exception {
        doReturn(ok()).when(mockHttp).send(any(HttpRequest.class), any());

        new SlackNotifier(mockHttp).notify(List.of(redFinding()), WEBHOOK, Severity.RED);

        verify(mockHttp).send(any(HttpRequest.class), any());
    }

    @Test
    void payloadContainsRequiredFields() throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        doReturn(ok()).when(mockHttp).send(captor.capture(), any());

        new SlackNotifier(mockHttp).notify(List.of(redFinding()), WEBHOOK, Severity.RED);

        HttpRequest req = captor.getValue();
        // Method must be POST
        assertThat(req.method()).isEqualTo("POST");
        // URI must be the webhook
        assertThat(req.uri().toString()).isEqualTo(WEBHOOK);
        // Content-Type must be JSON
        assertThat(req.headers().firstValue("Content-Type")).contains("application/json");
        // Body must be present (BodyPublisher is set)
        assertThat(req.bodyPublisher()).isPresent();
    }

    @Test
    void buildMessage_containsFlaggedCount_worstLevel_topFinding_link() {
        Finding red = redFinding(); // 87/100 RED, com.example:some-lib, 1.0.0 → 2.0.0
        String msg = SlackNotifier.buildMessage(List.of(red), red);

        assertThat(msg).contains("1 flagged");
        assertThat(msg).contains("RED");
        assertThat(msg).contains("87/100");
        assertThat(msg).contains("com.example:some-lib");
        assertThat(msg).contains("1.0.0 → 2.0.0");
        assertThat(msg).contains("https://marshalhq.dev");
    }

    @Test
    void buildMessage_pluralisesDependencyCount() {
        Finding a = redFinding();
        Finding b = redFinding();
        String msg = SlackNotifier.buildMessage(List.of(a, b), a);
        assertThat(msg).contains("2 flagged dependencies");
    }

    @Test
    void orangeFinding_orangeMinLevel_postsAlert() throws Exception {
        Coordinates c = new Coordinates("com.example", "lib", "3.0.0");
        Finding orange = new Finding(c, "2.0.0", "3.0.0", 62, Severity.ORANGE, List.of(), false, false);
        doReturn(ok()).when(mockHttp).send(any(HttpRequest.class), any());

        new SlackNotifier(mockHttp).notify(List.of(orange), WEBHOOK, Severity.ORANGE);

        verify(mockHttp).send(any(HttpRequest.class), any());
    }

    // ── non-200 response is logged but does not throw ─────────────────────────────

    @Test
    void non200Response_doesNotThrow() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> bad = mock(HttpResponse.class);
        when(bad.statusCode()).thenReturn(500);
        doReturn(bad).when(mockHttp).send(any(HttpRequest.class), any());

        // Must not throw — notification error never gates CI result
        new SlackNotifier(mockHttp).notify(List.of(redFinding()), WEBHOOK, Severity.RED);
    }

    // ── unresolved deps ignored ───────────────────────────────────────────────────

    @Test
    void unresolvedDepOnly_noHttpCall() throws Exception {
        Finding unres = Finding.unresolved(new Coordinates("com.example", "lib", "UNRESOLVED"));
        new SlackNotifier(mockHttp).notify(List.of(unres), WEBHOOK, Severity.RED);
        verifyNoInteractions(mockHttp);
    }
}

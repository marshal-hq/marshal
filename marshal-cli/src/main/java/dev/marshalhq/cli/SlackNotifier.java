package dev.marshalhq.cli;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.marshalhq.core.Finding;
import dev.marshalhq.core.Severity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Posts a plain-text Slack message when findings at or above {@code minLevel} are present.
 * No-op when {@code webhook} is blank. Failure to post is logged and swallowed —
 * a notification error must never gate the CI result.
 */
public class SlackNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;

    public SlackNotifier() {
        this.http = HttpClient.newHttpClient();
    }

    /**
     * Package-private: inject HttpClient for testing.
     */
    SlackNotifier(HttpClient http) {
        this.http = http;
    }

    /**
     * Sends a Slack alert if {@code webhook} is non-blank and at least one finding
     * has {@code riskLevel >= minLevel}.
     */
    public void notify(List<Finding> findings, String webhook, Severity minLevel) {
        if (webhook == null || webhook.isBlank()) {
            return;
        }

        List<Finding> qualifying = findings.stream()
                .filter(f -> !f.isUnresolved() && f.riskLevel() != null)
                .filter(f -> f.riskLevel().ordinal() >= minLevel.ordinal())
                .toList();

        if (qualifying.isEmpty()) {
            return;
        }

        Finding top = qualifying.stream()
                .max(Comparator.comparingInt(Finding::riskScore))
                .orElseThrow();

        String text = buildMessage(qualifying, top);

        try {
            String body = MAPPER.writeValueAsString(Map.of("text", text));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(webhook))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Slack webhook returned HTTP {}", resp.statusCode());
            }
        }
        catch (Exception e) {
            log.warn("Failed to send Slack notification: {}", e.getMessage());
        }
    }

    static String buildMessage(List<Finding> qualifying, Finding top) {
        String from = top.fromVersion() != null ? top.fromVersion() + " → " : "";
        return String.format(
                "⚠️ Marshal: %d flagged %s detected\n" +
                        "Worst: %s %d/100 — %s %s%s\n" +
                        "https://marshalhq.dev",
                qualifying.size(),
                qualifying.size() == 1 ? "dependency" : "dependencies",
                top.riskLevel().name(),
                top.riskScore(),
                top.coordinates().toGa(),
                from,
                top.toVersion()
        );
    }
}

package dev.marshalhq.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marshalhq.core.*;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonReporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant FIXED_AT = Instant.parse("2026-06-01T10:00:00Z");
    private static final String PROJECT    = "./pom.xml";

    private final JsonReporter reporter = new JsonReporter(PROJECT, FIXED_AT);

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private static Finding finding(String ga, String from, String to,
                                   int score, Severity level, List<RuleResult> signals) {
        String[] p = ga.split(":");
        return new Finding(new Coordinates(p[0], p[1], to),
            from, to, score, level, signals, false, false);
    }

    private static Finding green(String ga) {
        return finding(ga, "1.0.0", "1.0.1", 4, Severity.GREEN, List.of());
    }

    private static Finding unresolved(String ga) {
        String[] p = ga.split(":");
        return Finding.unresolved(new Coordinates(p[0], p[1], "UNRESOLVED"));
    }

    private JsonNode render(List<Finding> findings) throws Exception {
        StringWriter sw = new StringWriter();
        reporter.report(findings, new PrintWriter(sw));
        return MAPPER.readTree(sw.toString());
    }

    // ── schema contract ───────────────────────────────────────────────────────────

    @Test
    void schemaFieldIsString10() throws Exception {
        JsonNode root = render(List.of());
        assertThat(root.get("schema").asText()).isEqualTo("1.0");
    }

    @Test
    void scannedAtIsISO8601UTC() throws Exception {
        JsonNode root = render(List.of());
        assertThat(root.get("scanned_at").asText()).isEqualTo("2026-06-01T10:00:00Z");
    }

    @Test
    void projectFieldMatchesPomPath() throws Exception {
        JsonNode root = render(List.of());
        assertThat(root.get("project").asText()).isEqualTo(PROJECT);
    }

    // ── summary ───────────────────────────────────────────────────────────────────

    @Test
    void summaryHasAllRequiredFields() throws Exception {
        JsonNode summary = render(List.of()).get("summary");
        assertThat(summary.has("total_dependencies")).isTrue();
        assertThat(summary.has("flagged")).isTrue();
        assertThat(summary.has("risk_distribution")).isTrue();
    }

    @Test
    void riskDistributionHasAllFourLowercaseKeys() throws Exception {
        JsonNode dist = render(List.of()).get("summary").get("risk_distribution");
        assertThat(dist.has("red")).isTrue();
        assertThat(dist.has("orange")).isTrue();
        assertThat(dist.has("yellow")).isTrue();
        assertThat(dist.has("green")).isTrue();
    }

    @Test
    void distributionCountsCorrect_mixedSeverities() throws Exception {
        RuleResult sig = new RuleResult(35, Severity.ORANGE, "evidence", "NEW-MAINTAINER");
        List<Finding> findings = List.of(
            finding("a:b", "1.0", "2.0", 87, Severity.RED,    List.of(sig)),
            finding("c:d", "2.0", "3.0", 62, Severity.ORANGE, List.of(sig)),
            finding("e:f", "0.1", "0.2", 34, Severity.YELLOW, List.of(sig)),
            finding("e:f", "0.1", "0.2", 34, Severity.YELLOW, List.of(sig)),
            green("g:h"),
            green("i:j"),
            green("k:l")
        );

        JsonNode dist = render(findings).get("summary").get("risk_distribution");
        assertThat(dist.get("red").asInt()).isEqualTo(1);
        assertThat(dist.get("orange").asInt()).isEqualTo(1);
        assertThat(dist.get("yellow").asInt()).isEqualTo(2);
        assertThat(dist.get("green").asInt()).isEqualTo(3);
    }

    @Test
    void flaggedIsRedPlusOrange() throws Exception {
        RuleResult sig = new RuleResult(35, Severity.ORANGE, "ev", "NEW-MAINTAINER");
        List<Finding> findings = List.of(
            finding("a:b", "1.0", "2.0", 87, Severity.RED,    List.of(sig)),
            finding("c:d", "2.0", "3.0", 62, Severity.ORANGE, List.of(sig)),
            finding("e:f", "0.1", "0.2", 34, Severity.YELLOW, List.of(sig)),
            green("g:h")
        );

        JsonNode summary = render(findings).get("summary");
        assertThat(summary.get("flagged").asInt()).isEqualTo(2); // RED + ORANGE only
        assertThat(summary.get("total_dependencies").asInt()).isEqualTo(4);
    }

    @Test
    void distributionSumsToTotalMinusUnresolved() throws Exception {
        RuleResult sig = new RuleResult(40, Severity.RED, "ev", "SIG-DROPPED");
        List<Finding> findings = List.of(
            finding("a:b", "1.0", "2.0", 87, Severity.RED, List.of(sig)),
            green("c:d"),
            unresolved("e:f")  // must be excluded from distribution
        );

        JsonNode root    = render(findings);
        JsonNode dist    = root.get("summary").get("risk_distribution");
        int distributionTotal = dist.get("red").asInt() + dist.get("orange").asInt()
            + dist.get("yellow").asInt() + dist.get("green").asInt();

        assertThat(root.get("summary").get("total_dependencies").asInt()).isEqualTo(3);
        assertThat(distributionTotal).isEqualTo(2); // unresolved excluded
    }

    // ── findings array ────────────────────────────────────────────────────────────

    @Test
    void findingHasAllRequiredFields() throws Exception {
        RuleResult sig = new RuleResult(35, Severity.ORANGE, "key changed", "NEW-MAINTAINER");
        Finding f = finding("com.example:some-lib", "1.2.3", "2.0.0", 87, Severity.RED, List.of(sig));

        JsonNode finding = render(List.of(f)).get("findings").get(0);
        assertThat(finding.get("package").asText()).isEqualTo("com.example:some-lib");
        assertThat(finding.get("from_version").asText()).isEqualTo("1.2.3");
        assertThat(finding.get("to_version").asText()).isEqualTo("2.0.0");
        assertThat(finding.get("risk_score").asInt()).isEqualTo(87);
        assertThat(finding.get("risk_level").asText()).isEqualTo("red");
        assertThat(finding.get("signals").isArray()).isTrue();
    }

    @Test
    void riskLevelIsLowercase() throws Exception {
        RuleResult sig = new RuleResult(35, Severity.ORANGE, "ev", "NEW-MAINTAINER");
        Finding red    = finding("a:b", "1.0", "2.0", 87, Severity.RED,    List.of(sig));
        Finding orange = finding("c:d", "2.0", "3.0", 62, Severity.ORANGE, List.of(sig));
        Finding yellow = finding("e:f", "0.1", "0.2", 34, Severity.YELLOW, List.of(sig));
        Finding green_ = green("g:h");

        JsonNode arr = render(List.of(red, orange, yellow, green_)).get("findings");
        assertThat(arr.get(0).get("risk_level").asText()).isEqualTo("red");
        assertThat(arr.get(1).get("risk_level").asText()).isEqualTo("orange");
        assertThat(arr.get(2).get("risk_level").asText()).isEqualTo("yellow");
        assertThat(arr.get(3).get("risk_level").asText()).isEqualTo("green");
    }

    @Test
    void signalHasRuleAndContributionAndEvidence() throws Exception {
        RuleResult sig = new RuleResult(35, Severity.ORANGE, "key changed", "NEW-MAINTAINER");
        Finding f = finding("a:b", "1.0", "2.0", 87, Severity.RED, List.of(sig));

        JsonNode signal = render(List.of(f)).get("findings").get(0).get("signals").get(0);
        assertThat(signal.get("rule").asText()).isEqualTo("NEW-MAINTAINER");
        assertThat(signal.get("score_contribution").asInt()).isEqualTo(35);
        assertThat(signal.get("evidence").asText()).isEqualTo("key changed");
    }

    @Test
    void unresolvedDep_presentWithMarker() throws Exception {
        Finding f = unresolved("com.example:some-lib");
        JsonNode finding = render(List.of(f)).get("findings").get(0);

        assertThat(finding.get("package").asText()).isEqualTo("com.example:some-lib");
        assertThat(finding.get("risk_level").asText()).isEqualTo("unresolved");
        assertThat(finding.get("risk_score").asInt()).isEqualTo(0);
        assertThat(finding.get("signals").size()).isEqualTo(0);
    }

    @Test
    void firstSeenDep_fromVersionIsNull() throws Exception {
        Finding f = finding("com.example:new-lib", null, "1.0.0", 87, Severity.RED, List.of());
        JsonNode finding = render(List.of(f)).get("findings").get(0);
        assertThat(finding.get("from_version").isNull()).isTrue();
    }

    @Test
    void outputIsValidJson() throws Exception {
        RuleResult sig = new RuleResult(40, Severity.RED, "dropped", "SIG-DROPPED");
        List<Finding> findings = List.of(
            finding("a:b", "1.0", "2.0", 87, Severity.RED, List.of(sig)),
            green("c:d"),
            unresolved("e:f")
        );
        StringWriter sw = new StringWriter();
        reporter.report(findings, new PrintWriter(sw));
        // If this doesn't throw, the JSON is valid
        assertThat(MAPPER.readTree(sw.toString())).isNotNull();
    }
}

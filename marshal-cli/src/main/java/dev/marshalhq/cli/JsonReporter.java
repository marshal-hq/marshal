package dev.marshalhq.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.marshalhq.core.Finding;
import dev.marshalhq.core.Severity;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * JSON reporter. Matches marshal_02 schema v1.0 exactly — field names and types
 * are a frozen contract consumed by the GitHub Action.
 *
 * "flagged" = RED + ORANGE count (findings that appear in PR comments).
 * YELLOW findings appear in the findings array but are not counted in "flagged".
 * UNRESOLVED deps appear in findings with risk_level="unresolved" and are
 * excluded from risk_distribution counts.
 */
public class JsonReporter implements Reporter {

    private static final DateTimeFormatter ISO_UTC =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER =
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final String project;
    private final Instant scannedAt;

    public JsonReporter(String project, Instant scannedAt) {
        this.project   = project;
        this.scannedAt = scannedAt;
    }

    @Override
    public void report(List<Finding> findings, PrintWriter out) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("schema",     "1.0");
            root.put("scanned_at", ISO_UTC.format(scannedAt));
            root.put("project",    project);
            root.set("summary",   buildSummary(findings));
            root.set("findings",  buildFindings(findings));

            out.println(MAPPER.writeValueAsString(root));
        } catch (Exception e) {
            throw new RuntimeException("JSON serialisation failed", e);
        }
    }

    // ── summary ──────────────────────────────────────────────────────────────────

    private ObjectNode buildSummary(List<Finding> findings) {
        long red    = count(findings, Severity.RED);
        long orange = count(findings, Severity.ORANGE);
        long yellow = count(findings, Severity.YELLOW);
        long green  = count(findings, Severity.GREEN);

        ObjectNode dist = MAPPER.createObjectNode();
        dist.put("red",    (int) red);
        dist.put("orange", (int) orange);
        dist.put("yellow", (int) yellow);
        dist.put("green",  (int) green);

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("total_dependencies", findings.size());
        summary.put("flagged",            (int)(red + orange));
        summary.set("risk_distribution",  dist);
        return summary;
    }

    // ── findings array ────────────────────────────────────────────────────────────

    private ArrayNode buildFindings(List<Finding> findings) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Finding f : findings) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("package",      f.coordinates().toGa());
            if (f.fromVersion() != null) {
                node.put("from_version", f.fromVersion());
            } else {
                node.putNull("from_version");
            }
            node.put("to_version",   f.toVersion());
            node.put("risk_score",   f.isUnresolved() ? 0 : f.riskScore());
            node.put("risk_level",   f.isUnresolved() ? "unresolved"
                                                      : f.riskLevel().name().toLowerCase());

            ArrayNode signals = MAPPER.createArrayNode();
            if (!f.isUnresolved()) {
                for (var s : f.signals()) {
                    ObjectNode sig = MAPPER.createObjectNode();
                    sig.put("rule",               s.ruleId() != null ? s.ruleId() : "UNKNOWN");
                    sig.put("score_contribution", s.scoreContribution());
                    sig.put("evidence",           s.evidence());
                    signals.add(sig);
                }
            }
            node.set("signals", signals);
            arr.add(node);
        }
        return arr;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private static long count(List<Finding> findings, Severity level) {
        return findings.stream()
            .filter(f -> !f.isUnresolved() && f.riskLevel() == level)
            .count();
    }
}

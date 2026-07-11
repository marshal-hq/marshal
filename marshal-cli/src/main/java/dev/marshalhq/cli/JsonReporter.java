package dev.marshalhq.cli;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.marshalhq.core.Finding;
import dev.marshalhq.core.Severity;

/**
 * JSON reporter. Matches marshal_02 schema v1.0 exactly — field names and types
 * are a frozen contract consumed by the GitHub Action.
 * <p>
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
        this.project = project;
        this.scannedAt = scannedAt;
    }

    @Override
    public void report(ScanReport report, PrintWriter out) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("schema", "1.0");
            root.put("scanned_at", ISO_UTC.format(scannedAt));
            root.put("project", project);
            root.set("summary", buildSummary(report));
            root.set("findings", buildFindings(report.all()));

            out.println(MAPPER.writeValueAsString(root));
        }
        catch (Exception e) {
            throw new RuntimeException("JSON serialisation failed", e);
        }
    }

    // ── summary ──────────────────────────────────────────────────────────────────

    private ObjectNode buildSummary(ScanReport report) {
        ObjectNode dist = MAPPER.createObjectNode();
        dist.put("red", (int) report.count(Severity.RED));
        dist.put("orange", (int) report.count(Severity.ORANGE));
        dist.put("yellow", (int) report.count(Severity.YELLOW));
        dist.put("green", (int) report.count(Severity.GREEN));

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("total_dependencies", report.total());
        summary.put("flagged", report.flaggedCount());
        summary.set("risk_distribution", dist);
        return summary;
    }

    // ── findings array ────────────────────────────────────────────────────────────

    private ArrayNode buildFindings(List<Finding> findings) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Finding f : findings) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("package", f.coordinates().toGa());
            if (f.fromVersion() != null) {
                node.put("from_version", f.fromVersion());
            }
            else {
                node.putNull("from_version");
            }
            node.put("to_version", f.toVersion());
            node.put("risk_score", f.isUnresolved() ? 0 : f.riskScore());
            node.put("risk_level", f.isUnresolved() ? "unresolved"
                    : f.riskLevel().name().toLowerCase());
            // Additive, emitted only when true: the dep is scored, but its descriptor was
            // unreadable so its transitive subtree was never walked (unscanned).
            if (f.hasUnexpandedSubtree()) {
                node.put("subtree_unexpanded", true);
            }

            ArrayNode signals = MAPPER.createArrayNode();
            if (!f.isUnresolved()) {
                for (var s : f.signals()) {
                    ObjectNode sig = MAPPER.createObjectNode();
                    sig.put("rule", s.ruleId() != null ? s.ruleId() : "UNKNOWN");
                    sig.put("score_contribution", s.scoreContribution());
                    sig.put("evidence", s.evidence());
                    signals.add(sig);
                }
            }
            node.set("signals", signals);
            arr.add(node);
        }
        return arr;
    }
}

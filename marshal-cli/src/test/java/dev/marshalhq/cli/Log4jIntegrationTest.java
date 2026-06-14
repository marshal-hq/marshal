package dev.marshalhq.cli;

import dev.marshalhq.core.*;
import dev.marshalhq.registry.MavenCentralClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: fetches real log4j metadata from Maven Central and verifies
 * that REPO-CHANGED fires because log4j 1.2.17's POM SCM URL points to an RC tag.
 *
 * Requires network access.
 */
class Log4jIntegrationTest {

    @Test
    void log4j1217_repoChangedFires_scmUrlPointsToRcTag() {
        MavenCentralClient client = new MavenCentralClient();

        Coordinates cur  = new Coordinates("log4j", "log4j", "1.2.17");
        Coordinates prev = new Coordinates("log4j", "log4j", "1.2.16");

        VersionMetadata current  = client.fetchMetadata(cur);
        VersionMetadata previous = client.fetchMetadata(prev);

        PackageContext ctx = new PackageContext(cur, current, previous,
            List.of(previous), null, false);

        RuleEngine.EvaluationDetail detail = CliHelper.buildEngine().evaluateWithDetails(ctx);

        RuleResult repoChanged = detail.firedRules().stream()
            .filter(r -> "REPO-CHANGED".equals(r.ruleId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("REPO-CHANGED did not fire"));

        assertThat(repoChanged.evidence()).contains("v1_2_17_rc3");
    }
}

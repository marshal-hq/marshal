package dev.marshalhq.cli;

import dev.marshalhq.core.Coordinates;
import dev.marshalhq.core.PackageContext;
import dev.marshalhq.core.RuleEngine;
import dev.marshalhq.core.RuleResult;
import dev.marshalhq.core.Severity;
import dev.marshalhq.core.VersionMetadata;
import dev.marshalhq.registry.MavenCentralClient;
import dev.marshalhq.resolvers.PomDependencyResolver;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Log4jIntegrationTest {

    @Test
    void log4j1217_repoChangedDoesNotFire_tagOnlyChange() throws URISyntaxException {
        // log4j 1.2.17 SCM URL uses tags/v1_2_17_rc3 while 1.2.16 used tags/v1_2_16.
        // The base repository path is identical — updating the tag for a new version is expected
        // behavior, not a supply-chain signal.
        Path pom = Path.of(getClass().getResource("/log4j-app.pom.xml").toURI());

        Coordinates log4j = new PomDependencyResolver().resolve(pom).stream()
                .filter(c -> "log4j".equals(c.groupId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("log4j not found in resolved deps"));

        Coordinates prev = new Coordinates(log4j.groupId(), log4j.artifactId(), "1.2.16");

        MavenCentralClient client = new MavenCentralClient();
        VersionMetadata current  = client.fetchMetadata(log4j);
        VersionMetadata previous = client.fetchMetadata(prev);

        PackageContext ctx = new PackageContext(log4j, current, previous, List.of(previous), null, false);
        RuleEngine.EvaluationDetail detail = CliHelper.buildEngine().evaluateWithDetails(ctx);

        assertThat(detail.score().score()).isEqualTo(35);
        assertThat(detail.score().level()).isEqualTo(Severity.YELLOW);

        RuleResult newMaintainer = detail.firedRules().stream()
                .filter(r -> "NEW-MAINTAINER".equals(r.ruleId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("NEW-MAINTAINER did not fire"));

        assertThat(newMaintainer.evidence())
                .contains("D3EC499070C9C3D0")   // 1.2.16 signing key
                .contains("86E02C5A42196CA8");  // 1.2.17 signing key
    }

    @Test
    void javaxActivation111_sigDropped_scoresOrange() throws URISyntaxException {
        Path pom = Path.of(getClass().getResource("/javax-activation-app.pom.xml").toURI());

        Coordinates activation = new PomDependencyResolver().resolve(pom).stream()
                .filter(c -> "javax.activation".equals(c.groupId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("javax.activation not found in resolved deps"));

        Coordinates prev = new Coordinates(activation.groupId(), activation.artifactId(), "1.1-rev-1");

        MavenCentralClient client = new MavenCentralClient();
        VersionMetadata current  = client.fetchMetadata(activation);
        VersionMetadata previous = client.fetchMetadata(prev);

        PackageContext ctx = new PackageContext(activation, current, previous, List.of(previous), null, false);
        RuleEngine.EvaluationDetail detail = CliHelper.buildEngine().evaluateWithDetails(ctx);

        assertThat(detail.score().score()).isEqualTo(55);
        assertThat(detail.score().level()).isEqualTo(Severity.ORANGE);

        RuleResult sigDropped = detail.firedRules().stream()
                .filter(r -> "SIG-DROPPED".equals(r.ruleId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SIG-DROPPED did not fire"));

        assertThat(sigDropped.scoreContribution()).isEqualTo(40);
        assertThat(sigDropped.severity()).isEqualTo(Severity.RED);
        assertThat(sigDropped.evidence()).isEqualTo("GPG signature was present in prior releases but dropped in this version");

        RuleResult missingSig = detail.firedRules().stream()
                .filter(r -> "MISSING-SIG".equals(r.ruleId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("MISSING-SIG did not fire"));

        assertThat(missingSig.scoreContribution()).isEqualTo(15);
        assertThat(missingSig.severity()).isEqualTo(Severity.YELLOW);
        assertThat(missingSig.evidence()).isEqualTo("No GPG signature present for this release");
    }
}

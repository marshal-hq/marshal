package dev.marshalhq.core.replay;

import dev.marshalhq.core.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Replay: node-ipc protestware (npm, 2022)
 * Attack: Legitimate maintainer added destructive payload to install script.
 * Signals: INSTALL_SCRIPT_CHANGED (simulated via script content change)
 * Note: Since InstallScriptChangedRule is not in v0.1, we simulate with
 * SIGNATURE_DROPPED as a proxy signal — legitimate maintainer sabotage
 * often drops signing discipline too.
 */
public class NodeIpcReplayTest {

    @Test
    void shouldDetectNodeIpcSabotage() {
        Coordinates coords = new Coordinates("com.example", "node-ipc", "10.1.1");

        VersionMetadata previous = new VersionMetadata(
            new Coordinates("com.example", "node-ipc", "10.1.0"),
            "riaevangelist@example.com", "SIGKEY", true,
            List.of(), 8,
            "https://github.com/RIAEvangelist/node-ipc",
            Instant.now().minusSeconds(86400 * 7), false
        );

        // Maintainer sabotage: signature dropped + major version jump
        VersionMetadata current = new VersionMetadata(
            coords,
            "riaevangelist@example.com", // same maintainer
            null, false,                 // signature dropped
            List.of(), 8,
            "https://github.com/RIAEvangelist/node-ipc",
            Instant.now(), false
        );

        PackageContext ctx = new PackageContext(coords, current, previous, List.of(previous), null, false);
        RiskScore score = ReplayTestHelper.defaultEngine().evaluate(ctx);

        // SIGNATURE_DROPPED (40) + MISSING_SIGNATURE (15) = 55 → ORANGE
        assertThat(score.level()).isEqualTo(Severity.ORANGE);
        assertThat(score.score()).isBetween(51, 80);
    }
}

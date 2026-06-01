package dev.marshalhq.core.replay;

import dev.marshalhq.core.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Replay: node-ipc protestware (npm, 2022)
 * Attack: Legitimate maintainer added destructive payload via peacenotwar dependency.
 * Signals: SIGNATURE_DROPPED + MISSING_SIGNATURE + DEPENDENCY_EXPLOSION + YANKED
 * Historical: protest build was unsigned, added peacenotwar (ballooning dep count),
 * and was later yanked by the maintainer after community backlash.
 */
public class NodeIpcReplayTest {

    @Test
    void shouldDetectNodeIpcSabotage() {
        Coordinates coords = new Coordinates("com.example", "node-ipc", "10.1.1");

        VersionMetadata previous = new VersionMetadata(
            new Coordinates("com.example", "node-ipc", "10.1.0"),
            "riaevangelist@example.com", "SIGKEY", SignatureStatus.PRESENT,
            List.of(), 8,
            "https://github.com/RIAEvangelist/node-ipc",
            Instant.now().minusSeconds(86400 * 7), false
        );

        // Maintainer sabotage: unsigned protest build, peacenotwar inflated dep count, later yanked
        VersionMetadata current = new VersionMetadata(
            coords,
            "riaevangelist@example.com", // same maintainer
            null, SignatureStatus.ABSENT, // signature dropped — protest build was unsigned
            List.of(), 30,               // peacenotwar + transitive deps: 8 → 30
            "https://github.com/RIAEvangelist/node-ipc",
            Instant.now(), true          // yanked after community backlash
        );

        PackageContext ctx = new PackageContext(coords, current, previous, List.of(previous), null, false);
        RiskScore score = ReplayTestHelper.defaultEngine().evaluate(ctx);

        // SIG_DROPPED(40) + MISSING_SIG(15) + DEP_EXPLOSION(25) + YANKED(25) = 105 → 100 → RED
        assertThat(score.level()).isEqualTo(Severity.RED);
        assertThat(score.score()).isGreaterThanOrEqualTo(81);
    }
}

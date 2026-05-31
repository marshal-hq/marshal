package dev.marshalhq.core.replay;

import dev.marshalhq.core.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Replay: event-stream (npm, 2018)
 * Attack: Maintainer handed off package to malicious actor.
 * New maintainer added a malicious dependency (flatmap-stream).
 * Signals: NEW_MAINTAINER + DEPENDENCY_EXPLOSION + SIGNATURE_DROPPED + MISSING_SIGNATURE
 * Note: attacker lacked the original maintainer's signing key — published unsigned.
 */
public class EventStreamReplayTest {

    @Test
    void shouldDetectEventStreamAttack() {
        Coordinates coords = new Coordinates("com.example", "event-stream", "3.3.6");

        VersionMetadata previous = new VersionMetadata(
            new Coordinates("com.example", "event-stream", "3.3.4"),
            "dominictarr@example.com", "AABBCC", true,
            List.of(), 3, "https://github.com/dominictarr/event-stream",
            Instant.now().minusSeconds(86400 * 30), false
        );

        // Attacker: new maintainer + dependency explosion + signature dropped (no access to original key)
        VersionMetadata current = new VersionMetadata(
            coords,
            "right9ctrl@malicious.com",  // new maintainer
            null, false,                 // unsigned — attacker had no original signing key
            List.of(), 12,               // dependency explosion: 3 → 12
            "https://github.com/dominictarr/event-stream",
            Instant.now(), false
        );

        PackageContext ctx = new PackageContext(coords, current, previous,
            List.of(previous), null, false);

        RiskScore score = ReplayTestHelper.defaultEngine().evaluate(ctx);

        // NEW_MAINTAINER(35) + DEP_EXPLOSION(25) + SIGNATURE_DROPPED(40) + MISSING_SIG(15) = 115 → 100 → RED
        assertThat(score.level()).isEqualTo(Severity.RED);
        assertThat(score.score()).isGreaterThanOrEqualTo(81);
    }
}

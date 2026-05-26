package dev.marshalhq.core.replay;

import dev.marshalhq.core.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Replay: XZ Utils backdoor (2024)
 * Attack: Slow social engineering over 2 years. Attacker gained maintainer
 * trust, became co-maintainer, then introduced backdoor.
 * Note: This is the hardest attack to detect statically. Marshal would have
 * caught the maintainer addition as NEW_MAINTAINER signal.
 * Honest test: we assert YELLOW or above, not RED — XZ was subtle.
 */
public class XzUtilsReplayTest {

    @Test
    void shouldFlagXzMaintainerChange() {
        Coordinates coords = new Coordinates("org.tukaani", "xz", "5.6.0");

        VersionMetadata previous = new VersionMetadata(
            new Coordinates("org.tukaani", "xz", "5.4.5"),
            "lasse.collin@example.com", "LEGITKEY", true,
            List.of(), 4,
            "https://github.com/tukaani-project/xz",
            Instant.now().minusSeconds(86400 * 180), false
        );

        // Jia Tan added as new publisher
        VersionMetadata current = new VersionMetadata(
            coords,
            "jia.tan@example.com",    // new maintainer
            "NEWKEY", true,
            List.of(), 4,
            "https://github.com/tukaani-project/xz",
            Instant.now(), false
        );

        PackageContext ctx = new PackageContext(coords, current, previous,
            List.of(previous), null, false);

        RiskScore score = ReplayTestHelper.defaultEngine().evaluate(ctx);

        // NEW_MAINTAINER (35) — single signal, 35 ≤ 80 so no cap → YELLOW
        assertThat(score.level()).isIn(Severity.YELLOW, Severity.ORANGE);
        assertThat(score.score()).isGreaterThanOrEqualTo(21);
    }
}

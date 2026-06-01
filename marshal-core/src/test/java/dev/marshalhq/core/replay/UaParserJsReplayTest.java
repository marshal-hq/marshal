package dev.marshalhq.core.replay;

import dev.marshalhq.core.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Replay: ua-parser-js (npm, 2021)
 * Attack: Account takeover. Attacker published malicious version with
 * a postinstall script that downloaded and executed a trojan.
 * Signals: NEW_MAINTAINER + SIGNATURE_DROPPED
 */
public class UaParserJsReplayTest {

    @Test
    void shouldDetectUaParserJsAttack() {
        Coordinates coords = new Coordinates("com.example", "ua-parser-js", "0.7.29");

        VersionMetadata previous = new VersionMetadata(
            new Coordinates("com.example", "ua-parser-js", "0.7.28"),
            "faisalman@example.com", "LEGITKEY", SignatureStatus.PRESENT,
            List.of(), 2, "https://github.com/faisalman/ua-parser-js",
            Instant.now().minusSeconds(86400 * 14), false
        );

        // Attacker: new maintainer + signature dropped
        VersionMetadata current = new VersionMetadata(
            coords,
            "attacker@protonmail.com",   // new maintainer
            null, SignatureStatus.ABSENT, // signature dropped
            List.of("postinstall: malicious.sh"),
            2,
            "https://github.com/faisalman/ua-parser-js",
            Instant.now(), false
        );

        PackageContext ctx = new PackageContext(coords, current, previous,
            List.of(previous), null, false);

        RiskScore score = ReplayTestHelper.defaultEngine().evaluate(ctx);

        assertThat(score.level()).isEqualTo(Severity.RED);
        assertThat(score.score()).isGreaterThanOrEqualTo(81);
    }
}

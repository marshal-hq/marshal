package dev.marshalhq.core.replay;

import dev.marshalhq.core.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Replay: PyTorch-nightly dependency confusion (2022)
 * Attack: Attacker published a malicious package to PyPI with the same name
 * as an internal PyTorch dependency. First publish from a new account.
 * Signals: FIRST_PUBLISH_NEW_ACCOUNT (not in v0.1) — simulated via
 * MISSING_SIGNATURE + YANKED (registry yanks the malicious package after discovery).
 *
 * Fixture note: With no previous version, most rules don't fire.
 * MISSING_SIGNATURE (15) + YANKED (25) = 40 → YELLOW.
 */
public class PyTorchConfusionReplayTest {

    @Test
    void shouldFlagFirstPublishFromNewAccount() {
        Coordinates coords = new Coordinates("org.example", "torchtriton", "2022.12.29");

        // No previous version — first ever publish; yanked after discovery
        VersionMetadata current = new VersionMetadata(
            coords,
            "attacker@protonmail.com",
            null, false,
            List.of(), 15,
            null,   // no repo URL
            Instant.now(), true  // yanked after registry takedown
        );

        PackageContext ctx = new PackageContext(coords, current, null,
            List.of(), null, false);

        RiskScore score = ReplayTestHelper.defaultEngine().evaluate(ctx);

        // MISSING_SIGNATURE (15) + YANKED (25) = 40 → YELLOW
        assertThat(score.score()).isGreaterThanOrEqualTo(15);
        assertThat(score.level()).isNotEqualTo(Severity.GREEN);
    }
}

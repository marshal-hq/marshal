package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;
import dev.marshalhq.core.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NewMaintainerRuleTest {

    private final NewMaintainerRule rule = new NewMaintainerRule();

    @Test
    void firesWhenEmailChanges() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "eve@attacker.com");
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(35);
        assertThat(result.severity()).isEqualTo(Severity.ORANGE);
    }

    @Test
    void doesNotFireWhenEmailIsTheSame() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com");
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }

    @Test
    void doesNotFireWhenPreviousIsNull() {
        VersionMetadata current = TestFixtures.metadata("1.0.0", true, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, null));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }

    @Test
    void firesWhenGpgFingerprintChanges() {
        VersionMetadata current = TestFixtures.withFingerprint("2.0.0", true, "alice@example.com", "BBBBBBBB");
        VersionMetadata previous = TestFixtures.withFingerprint("1.0.0", true, "alice@example.com", "AAAAAAAA");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(35);
    }
}

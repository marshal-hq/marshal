package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;
import dev.marshalhq.core.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NewMaintainerRuleTest {

    private final NewMaintainerRule rule = new NewMaintainerRule();

    @Test
    void id_isCorrect() {
        assertThat(rule.id()).isEqualTo("NEW-MAINTAINER");
    }

    @Test
    void firesWhenEmailChanges() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "eve@attacker.com");
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(35);
        assertThat(result.severity()).isEqualTo(Severity.ORANGE);
        assertThat(result.evidence()).contains("alice@example.com").contains("eve@attacker.com");
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
    void doesNotFireWhenBothEmailsAreNull() {
        // Maven Central does not expose publisher email; null on both sides is the common path.
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, null);
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, null);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }

    @Test
    void firesWhenGpgFingerprintChanges() {
        VersionMetadata current = TestFixtures.withFingerprint("2.0.0", true, "alice@example.com", "BBBBBBBB");
        VersionMetadata previous = TestFixtures.withFingerprint("1.0.0", true, "alice@example.com", "AAAAAAAA");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(35);
        assertThat(result.evidence()).contains("AAAAAAAA").contains("BBBBBBBB");
    }
}

package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;
import dev.marshalhq.core.SignatureStatus;
import dev.marshalhq.core.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MissingSignatureRuleTest {

    private final MissingSignatureRule rule = new MissingSignatureRule();

    @Test
    void id_isCorrect() {
        assertThat(rule.id()).isEqualTo("MISSING-SIG");
    }

    @Test
    void firesWhenCurrentVersionHasNoGpgSignature() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", false, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, null));
        assertThat(result.scoreContribution()).isEqualTo(15);
        assertThat(result.severity()).isEqualTo(Severity.YELLOW);
    }

    @Test
    void doesNotFireWhenCurrentVersionHasGpgSignature() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, null));
        assertThat(result.scoreContribution()).isEqualTo(0);
        assertThat(result.severity()).isEqualTo(Severity.GREEN);
    }

    @Test
    void doesNotFireWhenPreviousIsNullAndCurrentIsSigned() {
        VersionMetadata current = TestFixtures.metadata("1.0.0", true, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, null));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }

    @Test
    void scoreContributionIsExactlyFifteen() {
        VersionMetadata current = TestFixtures.metadata("1.0.0", false, "alice@example.com");
        VersionMetadata previous = TestFixtures.metadata("0.9.0", true, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(15);
    }

    @Test
    void abstainWhenSignatureStatusIsUnknown() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", SignatureStatus.UNKNOWN, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, null));
        assertThat(result.scoreContribution()).isEqualTo(0);
        assertThat(result.severity()).isEqualTo(Severity.GREEN);
    }

    @Test
    void firesOnAbsentButNotOnUnknown() {
        VersionMetadata absent = TestFixtures.metadata("2.0.0", SignatureStatus.ABSENT, "alice@example.com");
        VersionMetadata unknown = TestFixtures.metadata("2.0.0", SignatureStatus.UNKNOWN, "alice@example.com");
        assertThat(rule.evaluate(TestFixtures.ctx(absent, null)).scoreContribution()).isEqualTo(15);
        assertThat(rule.evaluate(TestFixtures.ctx(unknown, null)).scoreContribution()).isEqualTo(0);
    }
}

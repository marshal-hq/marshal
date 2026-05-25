package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;
import dev.marshalhq.core.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MajorVersionJumpRuleTest {

    private final MajorVersionJumpRule rule = new MajorVersionJumpRule();

    @Test
    void firesOnJumpGreaterThanTwoMajorVersions() {
        VersionMetadata current = TestFixtures.metadata("5.0.0", true, "alice@example.com");
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(20);
        assertThat(result.severity()).isEqualTo(Severity.YELLOW);
        assertThat(result.evidence()).contains("4");
    }

    @Test
    void doesNotFireOnJumpOfOne() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com");
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }

    @Test
    void doesNotFireOnJumpOfTwo() {
        VersionMetadata current = TestFixtures.metadata("3.0.0", true, "alice@example.com");
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }

    @Test
    void handlesNonSemverGracefullyWithoutException() {
        VersionMetadata current = TestFixtures.metadata("not-a-version", true, "alice@example.com");
        VersionMetadata previous = TestFixtures.metadata("also-not-semver", true, "alice@example.com");
        assertThatCode(() -> rule.evaluate(TestFixtures.ctx(current, previous)))
            .doesNotThrowAnyException();
    }

    @Test
    void doesNotFireWhenPreviousIsNull() {
        VersionMetadata current = TestFixtures.metadata("5.0.0", true, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, null));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }
}

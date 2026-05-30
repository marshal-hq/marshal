package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;
import dev.marshalhq.core.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyExplosionRuleTest {

    private final DependencyExplosionRule rule = new DependencyExplosionRule();

    @Test
    void firesWhenDepCountGrowsMoreThanThreeTimes() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com", 16, "https://github.com/example/test", false);
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com", 5, "https://github.com/example/test", false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(25);
        assertThat(result.severity()).isEqualTo(Severity.ORANGE);
    }

    @Test
    void doesNotFireWhenGrowthIsExactlyThreeTimes() {
        // 5 → 15: 15 is not > 15, boundary does not fire
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com", 15, "https://github.com/example/test", false);
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com", 5, "https://github.com/example/test", false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }

    @Test
    void doesNotFireWhenPreviousIsNull() {
        VersionMetadata current = TestFixtures.metadata("1.0.0", true, "alice@example.com", 20, "https://github.com/example/test", false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, null));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }

    @Test
    void doesNotFireWhenPreviousDepCountIsZero() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com", 10, "https://github.com/example/test", false);
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com", 0, "https://github.com/example/test", false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }

    @Test
    void doesNotFireWhenDepCountDecreases() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com", 3, "https://github.com/example/test", false);
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com", 10, "https://github.com/example/test", false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }

    @Test
    void scoreContributionIsExactlyTwentyFive() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com", 40, "https://github.com/example/test", false);
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com", 4, "https://github.com/example/test", false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(25);
    }
}

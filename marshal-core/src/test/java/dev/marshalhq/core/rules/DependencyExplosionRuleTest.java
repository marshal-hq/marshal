package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;
import dev.marshalhq.core.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyExplosionRuleTest {

    private final DependencyExplosionRule rule = new DependencyExplosionRule();

    @Test
    void id_isCorrect() {
        assertThat(rule.id()).isEqualTo("DEP-EXPLOSION");
    }

    @Test
    void firesWhenDepCountGrowsMoreThanThreeTimes() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com", 16, "https://github.com/example/test", false);
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com", 5, "https://github.com/example/test", false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(25);
        assertThat(result.severity()).isEqualTo(Severity.ORANGE);
        // evidence must show the actual ratio (16/5 = 3.2x), not an arithmetic mutation artifact
        assertThat(result.evidence()).contains("3.2x increase");
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

    @Test
    void abstainWhenCurrentDepCountIsMinusOne() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com", -1, "https://github.com/example/test", false);
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com", 5, "https://github.com/example/test", false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }

    @Test
    void abstainWhenPreviousDepCountIsMinusOne() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com", 40, "https://github.com/example/test", false);
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com", -1, "https://github.com/example/test", false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }
}

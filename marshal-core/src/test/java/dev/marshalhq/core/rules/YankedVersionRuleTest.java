package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;
import dev.marshalhq.core.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YankedVersionRuleTest {

    private final YankedVersionRule rule = new YankedVersionRule();

    @Test
    void firesWhenCurrentVersionIsYanked() {
        VersionMetadata current = TestFixtures.metadata("1.0.0", true, "alice@example.com", 5, "https://github.com/example/test", true);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, null));
        assertThat(result.scoreContribution()).isEqualTo(25);
        assertThat(result.severity()).isEqualTo(Severity.ORANGE);
    }

    @Test
    void doesNotFireWhenCurrentVersionIsNotYanked() {
        VersionMetadata current = TestFixtures.metadata("1.0.0", true, "alice@example.com", 5, "https://github.com/example/test", false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, null));
        assertThat(result.scoreContribution()).isEqualTo(0);
        assertThat(result.severity()).isEqualTo(Severity.GREEN);
    }

    @Test
    void scoreContributionIsExactlyTwentyFive() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", false, "alice@example.com", 5, "https://github.com/example/test", true);
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com", 5, "https://github.com/example/test", false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(25);
    }
}

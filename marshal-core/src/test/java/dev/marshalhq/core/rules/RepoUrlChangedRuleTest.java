package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;
import dev.marshalhq.core.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepoUrlChangedRuleTest {

    private final RepoUrlChangedRule rule = new RepoUrlChangedRule();

    @Test
    void firesWhenRepoUrlChangesBetweenVersions() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com", 5, "https://github.com/new-owner/lib", false);
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com", 5, "https://github.com/original/lib", false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(20);
        assertThat(result.severity()).isEqualTo(Severity.YELLOW);
    }

    @Test
    void firesWhenRepoUrlIsRemoved() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com", 5, null, false);
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com", 5, "https://github.com/original/lib", false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(20);
    }

    @Test
    void doesNotFireWhenUrlStaysTheSame() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com", 5, "https://github.com/example/lib", false);
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com", 5, "https://github.com/example/lib", false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }

    @Test
    void doesNotFireWhenBothPreviousAndCurrentHaveNoUrl() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com", 5, null, false);
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com", 5, null, false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }

    @Test
    void doesNotFireWhenPreviousIsNull() {
        VersionMetadata current = TestFixtures.metadata("1.0.0", true, "alice@example.com", 5, "https://github.com/example/lib", false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, null));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }

    @Test
    void scoreContributionIsExactlyTwenty() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com", 5, "https://gitlab.com/example/lib", false);
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com", 5, "https://github.com/example/lib", false);
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(20);
    }
}

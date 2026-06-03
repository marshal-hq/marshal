package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;
import dev.marshalhq.core.SignatureStatus;
import dev.marshalhq.core.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureDroppedRuleTest {

    private final SignatureDroppedRule rule = new SignatureDroppedRule();

    @Test
    void id_isCorrect() {
        assertThat(rule.id()).isEqualTo("SIG-DROPPED");
    }

    @Test
    void firesWhenPreviousWasSignedAndCurrentIsNot() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", false, "alice@example.com");
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(40);
        assertThat(result.severity()).isEqualTo(Severity.RED);
    }

    @Test
    void doesNotFireWhenNeitherWasSigned() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", false, "alice@example.com");
        VersionMetadata previous = TestFixtures.metadata("1.0.0", false, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }

    @Test
    void doesNotFireWhenCurrentIsSigned() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", true, "alice@example.com");
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(0);
    }

    @Test
    void firesWhenTwoPriorHistoryReleasesWereSigned() {
        VersionMetadata current = TestFixtures.metadata("4.0.0", false, "alice@example.com");
        VersionMetadata h1 = TestFixtures.metadata("2.0.0", true, "alice@example.com");
        VersionMetadata h2 = TestFixtures.metadata("3.0.0", true, "alice@example.com");
        PackageContext ctx = TestFixtures.ctx(current, null, List.of(h1, h2));
        RuleResult result = rule.evaluate(ctx);
        assertThat(result.scoreContribution()).isEqualTo(40);
    }

    @Test
    void abstainWhenCurrentSignatureStatusIsUnknown() {
        VersionMetadata current = TestFixtures.metadata("2.0.0", SignatureStatus.UNKNOWN, "alice@example.com");
        VersionMetadata previous = TestFixtures.metadata("1.0.0", true, "alice@example.com");
        RuleResult result = rule.evaluate(TestFixtures.ctx(current, previous));
        assertThat(result.scoreContribution()).isEqualTo(0);
        assertThat(result.severity()).isEqualTo(Severity.GREEN);
    }

    @Test
    void doesNotCountUnknownHistoryEntriesAsSigned() {
        VersionMetadata current = TestFixtures.metadata("4.0.0", false, "alice@example.com");
        VersionMetadata h1 = TestFixtures.metadata("2.0.0", SignatureStatus.UNKNOWN, "alice@example.com");
        VersionMetadata h2 = TestFixtures.metadata("3.0.0", SignatureStatus.UNKNOWN, "alice@example.com");
        PackageContext ctx = TestFixtures.ctx(current, null, List.of(h1, h2));
        RuleResult result = rule.evaluate(ctx);
        assertThat(result.scoreContribution()).isEqualTo(0);
    }
}

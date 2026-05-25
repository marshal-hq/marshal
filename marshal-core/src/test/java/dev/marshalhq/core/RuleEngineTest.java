package dev.marshalhq.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    private static final PackageContext ANY_CTX = TestFixtures.ctx(
        TestFixtures.metadata("1.0.0", true, "alice@example.com"), null);

    @Test
    void singleSignalCannotExceed75EvenWhenRawIsHigher() {
        Rule highRule = new Rule() {
            public String id() { return "TEST-HIGH"; }
            public String description() { return "always fires 100"; }
            public RuleResult evaluate(PackageContext ctx) {
                return new RuleResult(100, Severity.RED, "fired");
            }
        };
        RuleEngine engine = new RuleEngine(List.of(highRule));
        RiskScore score = engine.evaluate(ANY_CTX);
        assertThat(score.score()).isEqualTo(75);
        assertThat(score.level()).isEqualTo(Severity.ORANGE);
    }

    @Test
    void highReputationScalesScoreDownByHalf() {
        Rule r = new Rule() {
            public String id() { return "TEST-60"; }
            public String description() { return "fires 60"; }
            public RuleResult evaluate(PackageContext ctx) {
                return new RuleResult(60, Severity.ORANGE, "fired");
            }
        };
        PackageContext highRepCtx = TestFixtures.ctx(
            TestFixtures.metadata("1.0.0", true, "alice@example.com"), null, true);
        RuleEngine engine = new RuleEngine(List.of(r));
        RiskScore score = engine.evaluate(highRepCtx);
        assertThat(score.score()).isEqualTo(30);
        assertThat(score.level()).isEqualTo(Severity.YELLOW);
    }

    @Test
    void twoSignalsCombineToRed() {
        Rule r1 = new Rule() {
            public String id() { return "TEST-A"; }
            public String description() { return "fires 45"; }
            public RuleResult evaluate(PackageContext ctx) {
                return new RuleResult(45, Severity.ORANGE, "signal A");
            }
        };
        Rule r2 = new Rule() {
            public String id() { return "TEST-B"; }
            public String description() { return "fires 45"; }
            public RuleResult evaluate(PackageContext ctx) {
                return new RuleResult(45, Severity.ORANGE, "signal B");
            }
        };
        RuleEngine engine = new RuleEngine(List.of(r1, r2));
        RiskScore score = engine.evaluate(ANY_CTX);
        assertThat(score.score()).isEqualTo(90);
        assertThat(score.level()).isEqualTo(Severity.RED);
    }

    @Test
    void noFiringRulesProducesGreenZero() {
        Rule quiet = new Rule() {
            public String id() { return "TEST-QUIET"; }
            public String description() { return "never fires"; }
            public RuleResult evaluate(PackageContext ctx) {
                return new RuleResult(0, Severity.GREEN, "");
            }
        };
        RuleEngine engine = new RuleEngine(List.of(quiet));
        RiskScore score = engine.evaluate(ANY_CTX);
        assertThat(score.score()).isEqualTo(0);
        assertThat(score.level()).isEqualTo(Severity.GREEN);
    }
}

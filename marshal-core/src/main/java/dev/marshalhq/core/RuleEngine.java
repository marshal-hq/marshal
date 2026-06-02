package dev.marshalhq.core;

import java.util.List;

public class RuleEngine {
    private final List<Rule> rules;

    public record EvaluationDetail(RiskScore score, List<RuleResult> firedRules) {}

    public RuleEngine(List<Rule> rules) {
        this.rules = rules;
    }

    public RiskScore evaluate(PackageContext ctx) {
        return evaluateWithDetails(ctx).score();
    }

    public EvaluationDetail evaluateWithDetails(PackageContext ctx) {
        List<RuleResult> fired = rules.stream()
            .map(r -> r.evaluate(ctx).withRuleId(r.id()))
            .filter(r -> r.scoreContribution() > 0)
            .toList();

        int raw = fired.stream().mapToInt(RuleResult::scoreContribution).sum();

        // High reputation packages: scale down
        if (ctx.isHighReputation()) {
            raw = (int)(raw * 0.5);
        }

        // Single-signal cap: never RED on one rule alone
        if (fired.size() == 1 && raw > 80) {
            raw = 75;
        }

        int score = Math.min(100, raw);
        return new EvaluationDetail(new RiskScore(score, RiskScore.levelFor(score)), fired);
    }

    public List<Rule> rules() { return rules; }
}

package dev.marshalhq.core;

/**
 * Per-rule output. {@code scoreContribution} is aggregated by RuleEngine.
 * {@code severity} is the rule's own label — used by reporters to annotate signals.
 * {@code ruleId} is the rule's short identifier; set by RuleEngine, null when unset.
 */
public record RuleResult(
        int scoreContribution,
        Severity severity,
        String evidence,
        String ruleId
) {

    /**
     * Convenience constructor used by all rule implementations (ruleId set by engine).
     */
    public RuleResult(int scoreContribution, Severity severity, String evidence) {
        this(scoreContribution, severity, evidence, null);
    }

    public RuleResult withScoreScaledBy(double factor) {
        return new RuleResult((int) (scoreContribution * factor), severity, evidence, ruleId);
    }

    public RuleResult withRuleId(String id) {
        return new RuleResult(scoreContribution, severity, evidence, id);
    }
}

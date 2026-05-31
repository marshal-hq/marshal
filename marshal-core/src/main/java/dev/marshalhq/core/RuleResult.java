package dev.marshalhq.core;

/**
 * Per-rule output. {@code scoreContribution} is aggregated by RuleEngine.
 * {@code severity} is the rule's own label — used by the terminal reporter
 * to annotate each fired rule; it is NOT used in score aggregation, which
 * re-buckets the summed score independently.
 */
public record RuleResult(
    int scoreContribution,
    Severity severity,
    String evidence
) {
    public RuleResult withScoreScaledBy(double factor) {
        return new RuleResult((int)(scoreContribution * factor), severity, evidence);
    }
}

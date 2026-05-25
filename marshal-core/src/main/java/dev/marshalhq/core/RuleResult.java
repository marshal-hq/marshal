package dev.marshalhq.core;

public record RuleResult(
    int scoreContribution,
    Severity severity,
    String evidence
) {
    public RuleResult withScoreScaledBy(double factor) {
        return new RuleResult((int)(scoreContribution * factor), severity, evidence);
    }
}

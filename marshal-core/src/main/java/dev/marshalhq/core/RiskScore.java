package dev.marshalhq.core;

public record RiskScore(int score, Severity level) {

    public static Severity levelFor(int score) {
        if (score >= 81) {
            return Severity.RED;
        }
        if (score >= 51) {
            return Severity.ORANGE;
        }
        if (score >= 21) {
            return Severity.YELLOW;
        }
        return Severity.GREEN;
    }
}

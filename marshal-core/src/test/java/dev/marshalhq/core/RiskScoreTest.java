package dev.marshalhq.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RiskScoreTest {

    @ParameterizedTest(name = "score {0} → {1}")
    @CsvSource({
        "0,  GREEN",
        "20, GREEN",
        "21, YELLOW",
        "50, YELLOW",
        "51, ORANGE",
        "80, ORANGE",
        "81, RED",
        "100, RED"
    })
    void levelFor_exactBoundaries(int score, Severity expected) {
        assertThat(RiskScore.levelFor(score)).isEqualTo(expected);
    }

    @Test
    void record_storesScoreAndLevel() {
        RiskScore rs = new RiskScore(75, Severity.ORANGE);
        assertThat(rs.score()).isEqualTo(75);
        assertThat(rs.level()).isEqualTo(Severity.ORANGE);
    }
}

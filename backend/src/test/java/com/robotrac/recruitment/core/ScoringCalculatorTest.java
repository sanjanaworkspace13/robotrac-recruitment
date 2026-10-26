package com.robotrac.recruitment.core;

import com.robotrac.recruitment.core.ScoringCalculator.Answer;
import com.robotrac.recruitment.core.ScoringCalculator.Score;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoringCalculatorTest {

    @Test
    void defaultSchemePlusOneZeroZero() {
        List<Answer> answers = List.of(
                new Answer('A', 'A'),   // correct
                new Answer('B', 'C'),   // incorrect
                new Answer(null, 'D'),  // unanswered
                new Answer('d', 'D'));  // correct (case-insensitive)
        Score s = ScoringCalculator.calculate(answers, BigDecimal.ONE, false, BigDecimal.ZERO);
        assertThat(s.correct()).isEqualTo(2);
        assertThat(s.incorrect()).isEqualTo(1);
        assertThat(s.unanswered()).isEqualTo(1);
        assertThat(s.score()).isEqualByComparingTo("2.00");
        assertThat(s.maxScore()).isEqualByComparingTo("4.00");
    }

    @Test
    void incorrectAndUnansweredScoreZeroWhenNegativeMarkingDisabled() {
        Score s = ScoringCalculator.calculate(List.of(new Answer('B', 'A'), new Answer(null, 'A')),
                BigDecimal.ONE, false, new BigDecimal("0.25"));
        assertThat(s.score()).isEqualByComparingTo("0");
    }

    @Test
    void negativeMarkingDeductsOnlyForIncorrect() {
        Score s = ScoringCalculator.calculate(List.of(new Answer('A', 'A'), new Answer('B', 'A'), new Answer(null, 'A')),
                new BigDecimal("2"), true, new BigDecimal("0.5"));
        assertThat(s.score()).isEqualByComparingTo("1.50");
        assertThat(s.maxScore()).isEqualByComparingTo("6.00");
    }

    @Test
    void missingAnswerKeyIsAnError() {
        assertThatThrownBy(() -> ScoringCalculator.calculate(List.of(new Answer('A', null)), BigDecimal.ONE, false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

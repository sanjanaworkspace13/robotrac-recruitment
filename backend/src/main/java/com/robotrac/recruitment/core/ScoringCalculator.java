package com.robotrac.recruitment.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure scoring rules. Defaults (per drive): +1 for correct, 0 for incorrect, 0 for unanswered.
 * When negative marking is enabled, each incorrect answer subtracts {@code negativeMarks}.
 * The total may go below zero in that case (documented policy); it is never clamped silently.
 * "Answered and marked for review" answers are evaluated like any other saved answer.
 */
public final class ScoringCalculator {

    private ScoringCalculator() {
    }

    /** selected = original letter chosen by the candidate (null = unanswered); correct = key letter. */
    public record Answer(Character selected, Character correct) {
    }

    public record Score(int correct, int incorrect, int unanswered, BigDecimal score, BigDecimal maxScore) {
    }

    public static Score calculate(List<Answer> answers, BigDecimal marksPerCorrect,
                                  boolean negativeMarkingEnabled, BigDecimal negativeMarks) {
        if (marksPerCorrect == null || marksPerCorrect.signum() <= 0) {
            throw new IllegalArgumentException("marksPerCorrect must be positive");
        }
        BigDecimal penalty = negativeMarkingEnabled && negativeMarks != null ? negativeMarks.abs() : BigDecimal.ZERO;
        int correct = 0;
        int incorrect = 0;
        int unanswered = 0;
        for (Answer a : answers) {
            if (a.correct() == null) {
                throw new IllegalArgumentException("A question in the paper has no answer key");
            }
            if (a.selected() == null) {
                unanswered++;
            } else if (Character.toUpperCase(a.selected()) == Character.toUpperCase(a.correct())) {
                correct++;
            } else {
                incorrect++;
            }
        }
        BigDecimal score = marksPerCorrect.multiply(BigDecimal.valueOf(correct))
                .subtract(penalty.multiply(BigDecimal.valueOf(incorrect)))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal max = marksPerCorrect.multiply(BigDecimal.valueOf(answers.size())).setScale(2, RoundingMode.HALF_UP);
        return new Score(correct, incorrect, unanswered, score, max);
    }
}

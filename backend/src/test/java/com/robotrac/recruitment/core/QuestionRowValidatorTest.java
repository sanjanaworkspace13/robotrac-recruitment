package com.robotrac.recruitment.core;

import com.robotrac.recruitment.core.QuestionRowValidator.Outcome;
import com.robotrac.recruitment.core.QuestionRowValidator.RawRow;
import com.robotrac.recruitment.core.QuestionRowValidator.ValidatedRow;
import com.robotrac.recruitment.domain.Difficulty;
import com.robotrac.recruitment.domain.QuestionReviewStatus;
import com.robotrac.recruitment.domain.Section;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionRowValidatorTest {

    private static RawRow row(int n, String code, String section, String difficulty, String text,
                              String a, String b, String c, String d, String key, String answer) {
        return new RawRow(n, code, section, difficulty, text, a, b, c, d, key, answer, "why", "1", "Needs SME review");
    }

    private static RawRow good(int n, String code, String text) {
        return row(n, code, "Quantitative Aptitude", "Easy", text, "1", "2", "3", "4", "B", "2");
    }

    private static ValidatedRow one(RawRow r) {
        return QuestionRowValidator.validate(List.of(r), Set.of()).get(0);
    }

    @Test
    void validRowIsAcceptedAndKeepsWorkbookReviewStatus() {
        ValidatedRow v = one(good(2, "RBT-1", "What is 1+1?"));
        assertThat(v.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(v.section()).isEqualTo(Section.QUANTITATIVE);
        assertThat(v.difficulty()).isEqualTo(Difficulty.EASY);
        assertThat(v.correctOption()).isEqualTo("B");
        assertThat(v.reviewStatus()).isEqualTo(QuestionReviewStatus.NEEDS_REVIEW);
    }

    @Test
    void sectionLabelsFromWorkbookAreRecognised() {
        assertThat(QuestionRowValidator.parseSection("Analytical Thinking & Problem-Solving")).isEqualTo(Section.ANALYTICAL);
        assertThat(QuestionRowValidator.parseSection("Analytical Thinking and Problem Solving")).isEqualTo(Section.ANALYTICAL);
        assertThat(QuestionRowValidator.parseSection("Logical Reasoning")).isEqualTo(Section.LOGICAL);
        assertThat(QuestionRowValidator.parseDifficulty("Moderate")).isEqualTo(Difficulty.MODERATE);
        assertThat(QuestionRowValidator.parseSection("Verbal")).isNull();
    }

    @Test
    void missingTextOrOptionIsRejected() {
        assertThat(one(row(3, "X1", "Logical Reasoning", "Easy", "  ", "a", "b", "c", "d", "A", null)).outcome())
                .isEqualTo(Outcome.REJECTED);
        ValidatedRow v = one(row(4, "X2", "Logical Reasoning", "Easy", "Q?", "a", "", "c", "d", "A", null));
        assertThat(v.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(v.issues()).anyMatch(i -> i.contains("Option B"));
    }

    @Test
    void invalidCategoryOrDifficultyIsRejected() {
        assertThat(one(row(5, "X3", "Verbal Ability", "Easy", "Q?", "a", "b", "c", "d", "A", null)).outcome())
                .isEqualTo(Outcome.REJECTED);
        assertThat(one(row(6, "X4", "Logical Reasoning", "Extreme", "Q?", "a", "b", "c", "d", "A", null)).outcome())
                .isEqualTo(Outcome.REJECTED);
    }

    @Test
    void missingAnswerKeyIsFlaggedNotInvented() {
        ValidatedRow v = one(row(7, "X5", "Logical Reasoning", "Hard", "Q?", "a", "b", "c", "d", "", null));
        assertThat(v.outcome()).isEqualTo(Outcome.FLAGGED);
        assertThat(v.correctOption()).isNull();
        assertThat(v.reviewStatus()).isEqualTo(QuestionReviewStatus.FLAGGED);
    }

    @Test
    void invalidOptionReferenceIsFlagged() {
        ValidatedRow v = one(row(8, "X6", "Logical Reasoning", "Hard", "Q?", "a", "b", "c", "d", "E", null));
        assertThat(v.outcome()).isEqualTo(Outcome.FLAGGED);
        assertThat(v.issues()).anyMatch(i -> i.contains("Invalid answer key"));
    }

    @Test
    void keyTextMismatchIsFlaggedWithoutEchoingTheAnswer() {
        ValidatedRow v = one(row(9, "X7", "Logical Reasoning", "Hard", "Q?", "alpha", "beta", "gamma", "delta", "A", "beta"));
        assertThat(v.outcome()).isEqualTo(Outcome.FLAGGED);
        assertThat(String.join(" ", v.issues())).doesNotContain("beta");
    }

    @Test
    void identicalOptionsAreFlagged() {
        // Mirrors RBT-034 in the supplied workbook (Option A and Option C both "NPUQS").
        ValidatedRow v = one(row(10, "X8", "Logical Reasoning", "Easy", "Code?", "NPUQS", "NPUPS", "NPUQS", "NPUST", "B", "NPUPS"));
        assertThat(v.outcome()).isEqualTo(Outcome.FLAGGED);
        assertThat(v.issues()).anyMatch(i -> i.contains("Option A and Option C are identical"));
    }

    @Test
    void duplicatesAreDetected() {
        List<ValidatedRow> v = QuestionRowValidator.validate(List.of(
                good(2, "D-1", "Same question?"),
                good(3, "D-1", "Different text"),          // duplicate ID -> rejected
                good(4, "D-2", "  same   QUESTION? ")      // duplicate wording -> flagged
        ), Set.of());
        assertThat(v.get(0).outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(v.get(1).outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(v.get(2).outcome()).isEqualTo(Outcome.FLAGGED);
    }
}

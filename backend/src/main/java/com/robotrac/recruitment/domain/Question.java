package com.robotrac.recruitment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A question from the HR question bank. The answer key and explanation stay on the server:
 * candidate-facing DTOs are built field by field and never include them.
 */
@Entity
@Table(name = "question")
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Question ID from the workbook, e.g. RBT-001. */
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Section section;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Difficulty difficulty;

    @Column(name = "question_text", nullable = false, length = 2000)
    private String text;

    @Column(name = "option_a", nullable = false, length = 500)
    private String optionA;

    @Column(name = "option_b", nullable = false, length = 500)
    private String optionB;

    @Column(name = "option_c", nullable = false, length = 500)
    private String optionC;

    @Column(name = "option_d", nullable = false, length = 500)
    private String optionD;

    /** 'A'..'D', or null when the workbook had no valid key (such questions are FLAGGED). */
    @Column(name = "correct_option", length = 1)
    private String correctOption;

    @Column(length = 2000)
    private String explanation;

    @Column(name = "source_marks", precision = 6, scale = 2)
    private BigDecimal sourceMarks;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 20)
    private QuestionReviewStatus reviewStatus = QuestionReviewStatus.NEEDS_REVIEW;

    @Column(name = "validation_notes", length = 1000)
    private String validationNotes;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "source_row")
    private Integer sourceRow;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Question() {
    }

    /** Text of option 'A'..'D'. */
    public String optionText(char letter) {
        return switch (letter) {
            case 'A' -> optionA;
            case 'B' -> optionB;
            case 'C' -> optionC;
            case 'D' -> optionD;
            default -> throw new IllegalArgumentException("Unknown option " + letter);
        };
    }

    /** Usable in a new paper: active, not flagged and has a valid answer key. */
    public boolean isEligible() {
        return active && reviewStatus != QuestionReviewStatus.FLAGGED && correctOption != null;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Section getSection() { return section; }
    public void setSection(Section section) { this.section = section; }
    public Difficulty getDifficulty() { return difficulty; }
    public void setDifficulty(Difficulty difficulty) { this.difficulty = difficulty; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getOptionA() { return optionA; }
    public void setOptionA(String optionA) { this.optionA = optionA; }
    public String getOptionB() { return optionB; }
    public void setOptionB(String optionB) { this.optionB = optionB; }
    public String getOptionC() { return optionC; }
    public void setOptionC(String optionC) { this.optionC = optionC; }
    public String getOptionD() { return optionD; }
    public void setOptionD(String optionD) { this.optionD = optionD; }
    public String getCorrectOption() { return correctOption; }
    public void setCorrectOption(String correctOption) { this.correctOption = correctOption; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public BigDecimal getSourceMarks() { return sourceMarks; }
    public void setSourceMarks(BigDecimal sourceMarks) { this.sourceMarks = sourceMarks; }
    public QuestionReviewStatus getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(QuestionReviewStatus reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getValidationNotes() { return validationNotes; }
    public void setValidationNotes(String validationNotes) { this.validationNotes = validationNotes; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Integer getSourceRow() { return sourceRow; }
    public void setSourceRow(Integer sourceRow) { this.sourceRow = sourceRow; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

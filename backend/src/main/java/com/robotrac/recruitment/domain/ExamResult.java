package com.robotrac.recruitment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** Server-computed score of a finalised attempt. Visible to HR only. */
@Entity
@Table(name = "exam_result")
public class ExamResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id", nullable = false, unique = true)
    private ExamAttempt attempt;

    @Column(name = "correct_count", nullable = false)
    private int correctCount;

    @Column(name = "incorrect_count", nullable = false)
    private int incorrectCount;

    @Column(name = "unanswered_count", nullable = false)
    private int unansweredCount;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal score;

    @Column(name = "max_score", nullable = false, precision = 8, scale = 2)
    private BigDecimal maxScore;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    protected ExamResult() {
    }

    public ExamResult(ExamAttempt attempt, int correctCount, int incorrectCount, int unansweredCount,
                      BigDecimal score, BigDecimal maxScore, Instant computedAt) {
        this.attempt = attempt;
        this.correctCount = correctCount;
        this.incorrectCount = incorrectCount;
        this.unansweredCount = unansweredCount;
        this.score = score;
        this.maxScore = maxScore;
        this.computedAt = computedAt;
    }

    public Long getId() { return id; }
    public ExamAttempt getAttempt() { return attempt; }
    public int getCorrectCount() { return correctCount; }
    public int getIncorrectCount() { return incorrectCount; }
    public int getUnansweredCount() { return unansweredCount; }
    public BigDecimal getScore() { return score; }
    public BigDecimal getMaxScore() { return maxScore; }
    public Instant getComputedAt() { return computedAt; }
}

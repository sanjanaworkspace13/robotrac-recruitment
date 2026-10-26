package com.robotrac.recruitment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A question placed in an attempt, its fixed position, its fixed option order and the candidate's response.
 *
 * <p>{@code optionOrder} is a permutation of "ABCD": the option shown at display index i is the
 * question's original option {@code optionOrder.charAt(i)}. The browser only ever sees display indexes 0-3,
 * so the original letters (and therefore the key) never leave the server.</p>
 */
@Entity
@Table(name = "attempt_question")
public class AttemptQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id", nullable = false)
    private ExamAttempt attempt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    /** 1-based question number shown to the candidate. */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "option_order", nullable = false, length = 4)
    private String optionOrder;

    /** Original option letter chosen ('A'..'D') or null when unanswered. */
    @Column(name = "selected_option", length = 1)
    private String selectedOption;

    @Column(name = "marked_for_review", nullable = false)
    private boolean markedForReview;

    @Column(nullable = false)
    private boolean visited;

    /** Highest client sequence number applied; older updates are rejected as stale. */
    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected AttemptQuestion() {
    }

    public AttemptQuestion(ExamAttempt attempt, Question question, int displayOrder, String optionOrder) {
        this.attempt = attempt;
        this.question = question;
        this.displayOrder = displayOrder;
        this.optionOrder = optionOrder;
    }

    public Long getId() { return id; }
    public ExamAttempt getAttempt() { return attempt; }
    public Question getQuestion() { return question; }
    public int getDisplayOrder() { return displayOrder; }
    public String getOptionOrder() { return optionOrder; }
    public String getSelectedOption() { return selectedOption; }
    public void setSelectedOption(String selectedOption) { this.selectedOption = selectedOption; }
    public boolean isMarkedForReview() { return markedForReview; }
    public void setMarkedForReview(boolean markedForReview) { this.markedForReview = markedForReview; }
    public boolean isVisited() { return visited; }
    public void setVisited(boolean visited) { this.visited = visited; }
    public long getLastSeq() { return lastSeq; }
    public void setLastSeq(long lastSeq) { this.lastSeq = lastSeq; }
    public Instant getAnsweredAt() { return answeredAt; }
    public void setAnsweredAt(Instant answeredAt) { this.answeredAt = answeredAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

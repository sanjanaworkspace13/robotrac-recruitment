package com.robotrac.recruitment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The single permitted attempt of one candidate at one drive.
 * (drive_id, candidate_id) is UNIQUE in the database, so a second attempt cannot exist.
 */
@Entity
@Table(name = "exam_attempt")
public class ExamAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "drive_id", nullable = false)
    private RecruitmentDrive drive;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private Candidate candidate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttemptStatus status = AttemptStatus.IN_PROGRESS;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "finalized_by", length = 30)
    private FinalizedBy finalizedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 30)
    private AttemptReviewStatus reviewStatus = AttemptReviewStatus.NOT_REVIEWED;

    @Column(name = "review_notes", length = 2000)
    private String reviewNotes;

    protected ExamAttempt() {
    }

    public ExamAttempt(RecruitmentDrive drive, Candidate candidate, Instant startedAt, Instant deadlineAt) {
        this.drive = drive;
        this.candidate = candidate;
        this.startedAt = startedAt;
        this.deadlineAt = deadlineAt;
        this.status = AttemptStatus.IN_PROGRESS;
    }

    public Long getId() { return id; }
    public RecruitmentDrive getDrive() { return drive; }
    public Candidate getCandidate() { return candidate; }
    public AttemptStatus getStatus() { return status; }
    public void setStatus(AttemptStatus status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public FinalizedBy getFinalizedBy() { return finalizedBy; }
    public void setFinalizedBy(FinalizedBy finalizedBy) { this.finalizedBy = finalizedBy; }
    public AttemptReviewStatus getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(AttemptReviewStatus reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getReviewNotes() { return reviewNotes; }
    public void setReviewNotes(String reviewNotes) { this.reviewNotes = reviewNotes; }
}

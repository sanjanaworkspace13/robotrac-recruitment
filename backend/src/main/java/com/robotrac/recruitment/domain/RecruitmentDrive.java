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
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/** A recruitment drive: one scheduled aptitude test with its own credentials and candidate list. */
@Entity
@Table(name = "recruitment_drive")
public class RecruitmentDrive {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(length = 1000)
    private String description;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DriveStatus status = DriveStatus.DRAFT;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "easy_count", nullable = false)
    private int easyCount;

    @Column(name = "moderate_count", nullable = false)
    private int moderateCount;

    @Column(name = "hard_count", nullable = false)
    private int hardCount;

    @Column(name = "section_balanced", nullable = false)
    private boolean sectionBalanced = true;

    @Column(name = "shuffle_options", nullable = false)
    private boolean shuffleOptions = true;

    @Column(name = "marks_per_correct", nullable = false, precision = 6, scale = 2)
    private BigDecimal marksPerCorrect = BigDecimal.ONE;

    @Column(name = "negative_marking_enabled", nullable = false)
    private boolean negativeMarkingEnabled = false;

    @Column(name = "negative_marks", nullable = false, precision = 6, scale = 2)
    private BigDecimal negativeMarks = BigDecimal.ZERO;

    @Column(name = "away_warning_threshold", nullable = false)
    private int awayWarningThreshold = 3;

    /** BCrypt hash of the shared drive password. Never returned by any API. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private AdminUser createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    @Column(nullable = false)
    private long version;

    public RecruitmentDrive() {
    }

    public int getTotalQuestions() {
        return easyCount + moderateCount + hardCount;
    }

    public BigDecimal getTotalMarks() {
        return marksPerCorrect.multiply(BigDecimal.valueOf(getTotalQuestions()));
    }

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    /** Phase relative to the given server time. */
    public DrivePhase phaseAt(Instant now) {
        return switch (status) {
            case DRAFT -> DrivePhase.DRAFT;
            case CLOSED -> DrivePhase.CLOSED;
            case ACTIVE -> now.isBefore(startAt) ? DrivePhase.SCHEDULED
                    : (now.isBefore(endAt) ? DrivePhase.LIVE : DrivePhase.ENDED);
        };
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant startAt) { this.startAt = startAt; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant endAt) { this.endAt = endAt; }
    public DriveStatus getStatus() { return status; }
    public void setStatus(DriveStatus status) { this.status = status; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public int getEasyCount() { return easyCount; }
    public void setEasyCount(int easyCount) { this.easyCount = easyCount; }
    public int getModerateCount() { return moderateCount; }
    public void setModerateCount(int moderateCount) { this.moderateCount = moderateCount; }
    public int getHardCount() { return hardCount; }
    public void setHardCount(int hardCount) { this.hardCount = hardCount; }
    public boolean isSectionBalanced() { return sectionBalanced; }
    public void setSectionBalanced(boolean sectionBalanced) { this.sectionBalanced = sectionBalanced; }
    public boolean isShuffleOptions() { return shuffleOptions; }
    public void setShuffleOptions(boolean shuffleOptions) { this.shuffleOptions = shuffleOptions; }
    public BigDecimal getMarksPerCorrect() { return marksPerCorrect; }
    public void setMarksPerCorrect(BigDecimal marksPerCorrect) { this.marksPerCorrect = marksPerCorrect; }
    public boolean isNegativeMarkingEnabled() { return negativeMarkingEnabled; }
    public void setNegativeMarkingEnabled(boolean negativeMarkingEnabled) { this.negativeMarkingEnabled = negativeMarkingEnabled; }
    public BigDecimal getNegativeMarks() { return negativeMarks; }
    public void setNegativeMarks(BigDecimal negativeMarks) { this.negativeMarks = negativeMarks; }
    public int getAwayWarningThreshold() { return awayWarningThreshold; }
    public void setAwayWarningThreshold(int awayWarningThreshold) { this.awayWarningThreshold = awayWarningThreshold; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public AdminUser getCreatedBy() { return createdBy; }
    public void setCreatedBy(AdminUser createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public long getVersion() { return version; }
}

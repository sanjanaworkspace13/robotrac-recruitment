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

/** "This candidate email may sit this drive." Revoked rather than deleted, to keep history. */
@Entity
@Table(name = "drive_authorization")
public class DriveAuthorization {

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
    private AuthorizationStatus status = AuthorizationStatus.AUTHORIZED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by")
    private AdminUser addedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected DriveAuthorization() {
    }

    public DriveAuthorization(RecruitmentDrive drive, Candidate candidate, AdminUser addedBy, Instant createdAt) {
        this.drive = drive;
        this.candidate = candidate;
        this.addedBy = addedBy;
        this.createdAt = createdAt;
        this.status = AuthorizationStatus.AUTHORIZED;
    }

    public Long getId() { return id; }
    public RecruitmentDrive getDrive() { return drive; }
    public Candidate getCandidate() { return candidate; }
    public AuthorizationStatus getStatus() { return status; }
    public void setStatus(AuthorizationStatus status) { this.status = status; }
    public AdminUser getAddedBy() { return addedBy; }
    public void setAddedBy(AdminUser addedBy) { this.addedBy = addedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}

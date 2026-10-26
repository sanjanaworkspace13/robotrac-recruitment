package com.robotrac.recruitment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A person identified by a normalised (trimmed, lower-case) email address. */
@Entity
@Table(name = "candidate")
public class Candidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "full_name", length = 120)
    private String fullName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Candidate() {
    }

    public Candidate(String email, String fullName, Instant createdAt) {
        this.email = email;
        this.fullName = fullName;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public Instant getCreatedAt() { return createdAt; }
}

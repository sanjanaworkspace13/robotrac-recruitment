package com.robotrac.recruitment.repository;

import com.robotrac.recruitment.domain.Candidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CandidateRepository extends JpaRepository<Candidate, Long> {

    /** Email must already be normalised (trimmed, lower-case). */
    Optional<Candidate> findByEmail(String email);
}

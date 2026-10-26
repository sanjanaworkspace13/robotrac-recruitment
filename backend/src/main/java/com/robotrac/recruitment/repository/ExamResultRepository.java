package com.robotrac.recruitment.repository;

import com.robotrac.recruitment.domain.ExamResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExamResultRepository extends JpaRepository<ExamResult, Long> {

    Optional<ExamResult> findByAttemptId(Long attemptId);

    boolean existsByAttemptId(Long attemptId);
}

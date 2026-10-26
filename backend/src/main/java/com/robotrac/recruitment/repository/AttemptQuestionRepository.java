package com.robotrac.recruitment.repository;

import com.robotrac.recruitment.domain.AttemptQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AttemptQuestionRepository extends JpaRepository<AttemptQuestion, Long> {

    @Query("select aq from AttemptQuestion aq join fetch aq.question where aq.attempt.id = :attemptId order by aq.displayOrder")
    List<AttemptQuestion> findByAttemptWithQuestion(@Param("attemptId") Long attemptId);

    Optional<AttemptQuestion> findByAttemptIdAndDisplayOrder(Long attemptId, int displayOrder);

    boolean existsByQuestionId(Long questionId);
}

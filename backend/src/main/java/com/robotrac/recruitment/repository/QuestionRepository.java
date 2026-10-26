package com.robotrac.recruitment.repository;

import com.robotrac.recruitment.domain.Difficulty;
import com.robotrac.recruitment.domain.Question;
import com.robotrac.recruitment.domain.QuestionReviewStatus;
import com.robotrac.recruitment.domain.Section;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    Optional<Question> findByCode(String code);

    @Query("select q.code from Question q")
    List<String> findAllCodes();

    @Query("select q.text from Question q")
    List<String> findAllTexts();

    /** Questions that may be placed in a new paper. */
    @Query("select q from Question q where q.active = true and q.reviewStatus <> :flagged and q.correctOption is not null")
    List<Question> findEligible(@Param("flagged") QuestionReviewStatus flagged);

    /** [section, difficulty, count] of eligible questions. */
    @Query("select q.section, q.difficulty, count(q) from Question q "
            + "where q.active = true and q.reviewStatus <> :flagged and q.correctOption is not null "
            + "group by q.section, q.difficulty")
    List<Object[]> countEligibleByCell(@Param("flagged") QuestionReviewStatus flagged);

    long countByReviewStatus(QuestionReviewStatus status);

    long countByActiveTrueAndReviewStatus(QuestionReviewStatus status);

    @Query("select q from Question q where (:section is null or q.section = :section) "
            + "and (:difficulty is null or q.difficulty = :difficulty) "
            + "and (:status is null or q.reviewStatus = :status) "
            + "and (:q is null or lower(q.text) like :q or lower(q.code) like :q)")
    Page<Question> search(@Param("section") Section section, @Param("difficulty") Difficulty difficulty,
                          @Param("status") QuestionReviewStatus status, @Param("q") String q, Pageable pageable);
}

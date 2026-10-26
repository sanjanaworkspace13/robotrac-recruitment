package com.robotrac.recruitment.repository;

import com.robotrac.recruitment.domain.AttemptStatus;
import com.robotrac.recruitment.domain.ExamAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExamAttemptRepository extends JpaRepository<ExamAttempt, Long> {

    Optional<ExamAttempt> findByDriveIdAndCandidateId(Long driveId, Long candidateId);

    /** Row lock: every state change of an attempt (save, submit, timeout) happens under this lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ExamAttempt a where a.id = :id")
    Optional<ExamAttempt> findByIdForUpdate(@Param("id") Long id);

    /**
     * Locks the candidate's attempt for this drive. Used instead of "read, then lock" so the entity is
     * always loaded fresh under the lock (a stale, earlier-read copy could otherwise hide a finalisation
     * done by another request in between).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ExamAttempt a where a.drive.id = :driveId and a.candidate.id = :candidateId")
    Optional<ExamAttempt> findForUpdate(@Param("driveId") Long driveId, @Param("candidateId") Long candidateId);

    @Query("select a.id from ExamAttempt a where a.status = :status and a.deadlineAt < :cutoff")
    List<Long> findIdsByStatusAndDeadlineBefore(@Param("status") AttemptStatus status, @Param("cutoff") Instant cutoff);

    long countByStatus(AttemptStatus status);

    boolean existsByDriveId(Long driveId);

    boolean existsByDriveIdAndCandidateId(Long driveId, Long candidateId);

    List<ExamAttempt> findByDriveIdAndCandidateIdIn(Long driveId, Collection<Long> candidateIds);

    /** [driveId, status, count] */
    @Query("select a.drive.id, a.status, count(a) from ExamAttempt a group by a.drive.id, a.status")
    List<Object[]> countByDriveAndStatus();
}

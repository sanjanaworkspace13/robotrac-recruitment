package com.robotrac.recruitment.service;

import com.robotrac.recruitment.core.ScoringCalculator;
import com.robotrac.recruitment.domain.ActorType;
import com.robotrac.recruitment.domain.AttemptQuestion;
import com.robotrac.recruitment.domain.AttemptStatus;
import com.robotrac.recruitment.domain.ExamAttempt;
import com.robotrac.recruitment.domain.ExamResult;
import com.robotrac.recruitment.domain.FinalizedBy;
import com.robotrac.recruitment.domain.RecruitmentDrive;
import com.robotrac.recruitment.repository.AttemptQuestionRepository;
import com.robotrac.recruitment.repository.ExamAttemptRepository;
import com.robotrac.recruitment.repository.ExamResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Closes an attempt and scores it on the server. Idempotent and race-safe:
 * <ul>
 *   <li>always called while holding the attempt's row lock (SELECT ... FOR UPDATE);</li>
 *   <li>does nothing if the attempt is already final, so a manual submit racing the timeout, a double
 *       click or a repeated request can never score twice;</li>
 *   <li>exam_result.attempt_id is UNIQUE in the database as a final safety net.</li>
 * </ul>
 */
@Service
public class FinalizationService {

    private final ExamAttemptRepository attempts;
    private final AttemptQuestionRepository attemptQuestions;
    private final ExamResultRepository results;
    private final ExamPolicy policy;
    private final AuditService audit;
    private final Clock clock;

    public FinalizationService(ExamAttemptRepository attempts, AttemptQuestionRepository attemptQuestions,
                               ExamResultRepository results, ExamPolicy policy, AuditService audit, Clock clock) {
        this.attempts = attempts;
        this.attemptQuestions = attemptQuestions;
        this.results = results;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    /** Must be called inside a transaction that already holds the row lock on {@code attempt}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean finalizeLocked(ExamAttempt attempt, AttemptStatus status, FinalizedBy by, Instant now) {
        if (attempt.getStatus().isFinal() || results.existsByAttemptId(attempt.getId())) {
            return false;
        }
        if (!status.isFinal()) {
            throw new IllegalArgumentException("Final status required");
        }
        RecruitmentDrive drive = attempt.getDrive();
        List<AttemptQuestion> paper = attemptQuestions.findByAttemptWithQuestion(attempt.getId());
        List<ScoringCalculator.Answer> answers = paper.stream()
                .map(aq -> new ScoringCalculator.Answer(
                        aq.getSelectedOption() == null ? null : aq.getSelectedOption().charAt(0),
                        aq.getQuestion().getCorrectOption() == null ? null : aq.getQuestion().getCorrectOption().charAt(0)))
                .toList();
        ScoringCalculator.Score score = ScoringCalculator.calculate(answers, drive.getMarksPerCorrect(),
                drive.isNegativeMarkingEnabled(), drive.getNegativeMarks());

        results.save(new ExamResult(attempt, score.correct(), score.incorrect(), score.unanswered(),
                score.score(), score.maxScore(), now));
        attempt.setStatus(status);
        attempt.setFinalizedBy(by);
        attempt.setSubmittedAt(now);
        audit.record(by == FinalizedBy.SERVER_DEADLINE ? ActorType.SYSTEM : ActorType.CANDIDATE,
                attempt.getCandidate().getId(), attempt.getCandidate().getEmail(), "ATTEMPT_FINALIZED",
                "ExamAttempt", attempt.getId(), "status=" + status + " by=" + by, null);
        return true;
    }

    /** Scheduler entry point: one short transaction per expired attempt. */
    @Transactional
    public boolean finalizeIfExpired(Long attemptId) {
        Instant now = clock.instant();
        ExamAttempt attempt = attempts.findByIdForUpdate(attemptId).orElse(null);
        if (attempt == null || attempt.getStatus().isFinal() || !policy.isExpired(attempt, now)) {
            return false;
        }
        return finalizeLocked(attempt, AttemptStatus.TIMED_OUT, FinalizedBy.SERVER_DEADLINE, now);
    }
}

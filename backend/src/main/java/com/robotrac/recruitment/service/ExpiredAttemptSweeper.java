package com.robotrac.recruitment.service;

import com.robotrac.recruitment.domain.AttemptStatus;
import com.robotrac.recruitment.repository.ExamAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Finalises attempts whose deadline has passed even if the candidate's browser is closed or offline.
 * Runs every {@code app.exam.expiry-sweep-ms} (default 15 s). Each attempt is handled in its own
 * transaction, under its row lock.
 */
@Component
public class ExpiredAttemptSweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpiredAttemptSweeper.class);

    private final ExamAttemptRepository attempts;
    private final FinalizationService finalizationService;
    private final ExamPolicy policy;
    private final Clock clock;

    public ExpiredAttemptSweeper(ExamAttemptRepository attempts, FinalizationService finalizationService,
                                 ExamPolicy policy, Clock clock) {
        this.attempts = attempts;
        this.finalizationService = finalizationService;
        this.policy = policy;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.exam.expiry-sweep-ms:15000}", initialDelayString = "${app.exam.expiry-sweep-ms:15000}")
    public void scheduledSweep() {
        sweep();
    }

    /** @return number of attempts finalised */
    public int sweep() {
        Instant cutoff = clock.instant().minusSeconds(policy.graceSeconds());
        List<Long> ids = attempts.findIdsByStatusAndDeadlineBefore(AttemptStatus.IN_PROGRESS, cutoff);
        int done = 0;
        for (Long id : ids) {
            try {
                if (finalizationService.finalizeIfExpired(id)) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.error("Could not finalise expired attempt {}: {}", id, e.getMessage());
            }
        }
        if (done > 0) {
            log.info("Finalised {} expired attempt(s).", done);
        }
        return done;
    }
}

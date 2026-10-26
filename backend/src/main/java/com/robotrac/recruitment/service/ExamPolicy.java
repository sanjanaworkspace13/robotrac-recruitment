package com.robotrac.recruitment.service;

import com.robotrac.recruitment.domain.ExamAttempt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Timing rules shared by every part of the exam flow.
 *
 * <ul>
 *   <li>The deadline is fixed by the server when the attempt starts:
 *       {@code deadline = min(start + duration, drive end time)}.</li>
 *   <li>The candidate sees {@code deadline - serverNow}. Refreshing, reopening the browser or changing the
 *       computer clock cannot change it.</li>
 *   <li>An answer save that reaches the server up to {@code app.exam.save-grace-seconds} (default 2 s) after
 *       the deadline is still accepted, to absorb network delay for a click made just before time ran out.
 *       After that the attempt is closed: answers already confirmed by the server are scored; anything the
 *       browser had not managed to save is not.</li>
 * </ul>
 */
@Component
public class ExamPolicy {

    private final long graceSeconds;

    public ExamPolicy(@Value("${app.exam.save-grace-seconds:2}") long graceSeconds) {
        this.graceSeconds = Math.max(0, graceSeconds);
    }

    public long graceSeconds() {
        return graceSeconds;
    }

    /** True once the attempt can no longer accept anything (deadline + grace has passed). */
    public boolean isExpired(ExamAttempt attempt, Instant now) {
        return now.isAfter(attempt.getDeadlineAt().plusSeconds(graceSeconds));
    }

    public long remainingSeconds(ExamAttempt attempt, Instant now) {
        long s = Duration.between(now, attempt.getDeadlineAt()).getSeconds();
        return Math.max(0, s);
    }
}

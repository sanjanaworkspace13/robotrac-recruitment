package com.robotrac.recruitment.service;

import com.robotrac.recruitment.core.FixedWindowRateLimiter;
import com.robotrac.recruitment.domain.AttemptStatus;
import com.robotrac.recruitment.domain.EventSource;
import com.robotrac.recruitment.domain.ExamAttempt;
import com.robotrac.recruitment.domain.FinalizedBy;
import com.robotrac.recruitment.domain.MonitoringEvent;
import com.robotrac.recruitment.domain.MonitoringEventType;
import com.robotrac.recruitment.exception.ApiException;
import com.robotrac.recruitment.exception.AttemptClosedException;
import com.robotrac.recruitment.repository.ExamAttemptRepository;
import com.robotrac.recruitment.repository.MonitoringEventRepository;
import com.robotrac.recruitment.security.CandidatePrincipal;
import com.robotrac.recruitment.web.dto.ExamDtos.MonitoringAckDto;
import com.robotrac.recruitment.web.dto.ExamDtos.MonitoringEventRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Records exam-integrity events reported by the exam page.
 *
 * <p>Anti-tampering rules: the candidate and attempt come from the server session, never from the request;
 * the server sets the authoritative timestamp; the browser cannot send a count (counts are computed by
 * counting stored rows); each browser event carries a unique id so retries are not double-counted; the same
 * event type arriving again within one second is treated as the same transition; events are rate limited.
 * Server-only event types (LOGIN, ATTEMPT_STARTED) cannot be reported by the browser.</p>
 *
 * <p>Limitations (also stated in the README): a web page can only see its own tab/window. It cannot detect
 * other devices, phones, screenshots, a second monitor or activity outside the browser, and a window can
 * lose focus for innocent reasons. Events are evidence for HR review, not automatic verdicts.</p>
 */
@Service
public class MonitoringService {

    public static final Set<MonitoringEventType> AWAY_TYPES = EnumSet.copyOf(
            Arrays.stream(MonitoringEventType.values()).filter(MonitoringEventType::isAway).toList());
    private static final Duration COLLAPSE_WINDOW = Duration.ofSeconds(1);

    private final ExamAttemptRepository attempts;
    private final MonitoringEventRepository events;
    private final FinalizationService finalization;
    private final ExamPolicy policy;
    private final FixedWindowRateLimiter limiter;
    private final Clock clock;
    private final int eventsPerMinute;

    public MonitoringService(ExamAttemptRepository attempts, MonitoringEventRepository events,
                             FinalizationService finalization, ExamPolicy policy, FixedWindowRateLimiter limiter,
                             Clock clock, @Value("${app.rate-limit.monitoring-events-per-minute:40}") int eventsPerMinute) {
        this.attempts = attempts;
        this.events = events;
        this.finalization = finalization;
        this.policy = policy;
        this.limiter = limiter;
        this.clock = clock;
        this.eventsPerMinute = eventsPerMinute;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public MonitoringAckDto recordClientEvent(CandidatePrincipal p, MonitoringEventRequest req) {
        MonitoringEventType type;
        try {
            type = MonitoringEventType.valueOf(req.type().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_EVENT_TYPE", "Unknown monitoring event type.");
        }
        if (!type.isClientReportable()) {
            throw ApiException.badRequest("INVALID_EVENT_TYPE", "This event type can only be recorded by the server.");
        }
        if (!limiter.tryAcquire("monitor:" + p.candidateId() + ":" + p.driveId(), eventsPerMinute, Duration.ofMinutes(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many monitoring events.");
        }
        Instant now = clock.instant();
        ExamAttempt attempt = attempts.findForUpdate(p.driveId(), p.candidateId())
                .orElseThrow(() -> ApiException.notFound("Exam attempt"));
        if (attempt.getStatus().isFinal()) {
            throw new AttemptClosedException("Your test has already been submitted.");
        }
        if (policy.isExpired(attempt, now)) {
            finalization.finalizeLocked(attempt, AttemptStatus.TIMED_OUT, FinalizedBy.SERVER_DEADLINE, now);
            throw new AttemptClosedException("Time is up. Your saved answers have been submitted.");
        }

        boolean duplicate = events.existsByAttemptIdAndClientEventId(attempt.getId(), req.clientEventId());
        if (!duplicate) {
            MonitoringEvent last = events.findFirstByAttemptIdOrderByServerTimeDescIdDesc(attempt.getId()).orElse(null);
            duplicate = last != null && last.getEventType() == type && last.getSource() == EventSource.CLIENT
                    && Duration.between(last.getServerTime(), now).compareTo(COLLAPSE_WINDOW) < 0;
        }
        if (!duplicate) {
            events.save(new MonitoringEvent(attempt, type, EventSource.CLIENT, now, plausibleClientTime(req.clientTime(), now),
                    req.clientEventId(), sanitize(req.detail())));
        }
        long away = events.countByAttemptIdAndEventTypeIn(attempt.getId(), AWAY_TYPES);
        return new MonitoringAckDto(!duplicate, duplicate, away, attempt.getDrive().getAwayWarningThreshold());
    }

    /** Server-verified events (login, attempt start). Caller supplies the attempt inside its transaction. */
    @Transactional
    public void recordServerEvent(ExamAttempt attempt, MonitoringEventType type, String detail) {
        events.save(new MonitoringEvent(attempt, type, EventSource.SERVER, clock.instant(), null, null, sanitize(detail)));
    }

    @Transactional(readOnly = true)
    public long awayCount(Long attemptId) {
        return events.countByAttemptIdAndEventTypeIn(attemptId, AWAY_TYPES);
    }

    /** Browser clocks can be wrong; keep the client time only if it is within a day of the server time. */
    private static Instant plausibleClientTime(Instant clientTime, Instant now) {
        if (clientTime == null) {
            return null;
        }
        return Duration.between(clientTime, now).abs().compareTo(Duration.ofDays(1)) <= 0 ? clientTime : null;
    }

    private static String sanitize(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String clean = detail.replaceAll("[\\p{Cntrl}]", " ").strip();
        return clean.length() > 200 ? clean.substring(0, 200) : clean;
    }
}

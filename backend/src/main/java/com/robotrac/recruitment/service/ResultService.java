package com.robotrac.recruitment.service;

import com.robotrac.recruitment.core.CsvUtil;
import com.robotrac.recruitment.domain.AttemptQuestion;
import com.robotrac.recruitment.domain.AttemptReviewStatus;
import com.robotrac.recruitment.domain.AttemptStatus;
import com.robotrac.recruitment.domain.Candidate;
import com.robotrac.recruitment.domain.ExamAttempt;
import com.robotrac.recruitment.domain.ExamResult;
import com.robotrac.recruitment.domain.MonitoringEvent;
import com.robotrac.recruitment.domain.MonitoringEventType;
import com.robotrac.recruitment.domain.Question;
import com.robotrac.recruitment.domain.RecruitmentDrive;
import com.robotrac.recruitment.exception.ApiException;
import com.robotrac.recruitment.repository.AttemptQuestionRepository;
import com.robotrac.recruitment.repository.ExamAttemptRepository;
import com.robotrac.recruitment.repository.ExamResultRepository;
import com.robotrac.recruitment.repository.MonitoringEventRepository;
import com.robotrac.recruitment.security.AdminPrincipal;
import com.robotrac.recruitment.web.dto.PageResponse;
import com.robotrac.recruitment.web.dto.ResultDtos.AttemptReviewRequest;
import com.robotrac.recruitment.web.dto.ResultDtos.EventDto;
import com.robotrac.recruitment.web.dto.ResultDtos.ResultDetailDto;
import com.robotrac.recruitment.web.dto.ResultDtos.ResultQuestionDto;
import com.robotrac.recruitment.web.dto.ResultDtos.ResultRowDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HR-only results, monitoring history and CSV export. Reached exclusively through /api/admin/** which
 * Spring Security restricts to ROLE_ADMIN, so a candidate cannot read results by guessing an attempt id.
 */
@Service
public class ResultService {

    /** Sort keys accepted from the browser, mapped to fixed JPQL expressions (never concatenated input). */
    private static final Map<String, String> SORTS = Map.of(
            "startedAt", "a.startedAt",
            "submittedAt", "a.submittedAt",
            "score", "r.score",
            "email", "c.email",
            "drive", "d.code",
            "status", "a.status");

    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ISO_INSTANT;

    private final EntityManager em;
    private final ExamAttemptRepository attempts;
    private final AttemptQuestionRepository attemptQuestions;
    private final ExamResultRepository results;
    private final MonitoringEventRepository events;
    private final AuditService audit;

    public ResultService(EntityManager em, ExamAttemptRepository attempts, AttemptQuestionRepository attemptQuestions,
                         ExamResultRepository results, MonitoringEventRepository events, AuditService audit) {
        this.em = em;
        this.attempts = attempts;
        this.attemptQuestions = attemptQuestions;
        this.results = results;
        this.events = events;
        this.audit = audit;
    }

    public record Filter(Long driveId, AttemptStatus status, String q) {
    }

    @Transactional(readOnly = true)
    public PageResponse<ResultRowDto> search(Filter filter, String sort, String direction, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        String orderBy = SORTS.getOrDefault(sort == null ? "" : sort, "a.startedAt");
        String dir = "asc".equalsIgnoreCase(direction) ? "asc" : "desc";

        TypedQuery<Object[]> query = em.createQuery("select a, c, d, r, "
                + "(select count(e) from MonitoringEvent e where e.attempt = a and e.eventType in :awayTypes) "
                + fromWhere(filter) + " order by " + orderBy + " " + dir + ", a.id desc", Object[].class);
        bind(query, filter);
        query.setParameter("awayTypes", MonitoringService.AWAY_TYPES);
        query.setFirstResult(safePage * safeSize);
        query.setMaxResults(safeSize);
        List<ResultRowDto> rows = query.getResultList().stream().map(ResultService::toRow).toList();

        TypedQuery<Long> count = em.createQuery("select count(a) " + fromWhere(filter), Long.class);
        bind(count, filter);
        return PageResponse.of(rows, safePage, safeSize, count.getSingleResult());
    }

    @Transactional(readOnly = true)
    public ResultDetailDto detail(Long attemptId) {
        ExamAttempt a = attempts.findById(attemptId).orElseThrow(() -> ApiException.notFound("Attempt"));
        ExamResult r = results.findByAttemptId(attemptId).orElse(null);
        List<MonitoringEvent> history = events.findByAttemptIdOrderByServerTimeAscIdAsc(attemptId);
        long away = history.stream().filter(e -> e.getEventType().isAway()).count();

        Map<String, Long> counts = new LinkedHashMap<>();
        for (MonitoringEventType t : MonitoringEventType.values()) {
            counts.put(t.name(), 0L);
        }
        for (MonitoringEvent e : history) {
            counts.merge(e.getEventType().name(), 1L, Long::sum);
        }
        List<EventDto> eventDtos = history.stream().map(e -> new EventDto(e.getId(), e.getEventType().name(),
                e.getEventType().getDescription(), e.getSource().name(), e.getEventType().isAway(), e.getServerTime(),
                e.getClientTime(), e.getDetail())).toList();

        List<ResultQuestionDto> questions = attemptQuestions.findByAttemptWithQuestion(attemptId).stream()
                .map(ResultService::toQuestion).toList();
        ResultRowDto row = toRow(new Object[]{a, a.getCandidate(), a.getDrive(), r, away});
        return new ResultDetailDto(row, a.getReviewNotes(), a.getDrive().getAwayWarningThreshold(), counts, eventDtos, questions);
    }

    @Transactional(readOnly = true)
    public List<EventDto> events(Long attemptId) {
        if (!attempts.existsById(attemptId)) {
            throw ApiException.notFound("Attempt");
        }
        return events.findByAttemptIdOrderByServerTimeAscIdAsc(attemptId).stream()
                .map(e -> new EventDto(e.getId(), e.getEventType().name(), e.getEventType().getDescription(),
                        e.getSource().name(), e.getEventType().isAway(), e.getServerTime(), e.getClientTime(), e.getDetail()))
                .toList();
    }

    @Transactional
    public ResultDetailDto review(Long attemptId, AttemptReviewRequest req, AdminPrincipal actor, String ip) {
        ExamAttempt a = attempts.findById(attemptId).orElseThrow(() -> ApiException.notFound("Attempt"));
        a.setReviewStatus(AttemptReviewStatus.valueOf(req.reviewStatus()));
        a.setReviewNotes(req.notes() == null || req.notes().isBlank() ? null : req.notes().trim());
        audit.admin(actor, "ATTEMPT_REVIEWED", "ExamAttempt", attemptId, "reviewStatus=" + req.reviewStatus(), ip);
        return detail(attemptId);
    }

    /** CSV of the filtered results (max 20 000 rows). Cells are escaped against formula injection. */
    @Transactional(readOnly = true)
    public String exportCsv(Filter filter, AdminPrincipal actor, String ip) {
        TypedQuery<Object[]> query = em.createQuery("select a, c, d, r, "
                + "(select count(e) from MonitoringEvent e where e.attempt = a and e.eventType in :awayTypes) "
                + fromWhere(filter) + " order by d.code asc, c.email asc", Object[].class);
        bind(query, filter);
        query.setParameter("awayTypes", MonitoringService.AWAY_TYPES);
        query.setMaxResults(20_000);
        StringBuilder csv = new StringBuilder("﻿"); // BOM so Excel opens UTF-8 correctly
        csv.append(CsvUtil.row("Attempt ID", "Candidate email", "Candidate name", "Drive code", "Drive name", "Status",
                "Finalised by", "Started (UTC)", "Deadline (UTC)", "Submitted (UTC)", "Score", "Max score", "Correct",
                "Incorrect", "Unanswered", "Left-exam events", "Above warning threshold", "HR review status"));
        int n = 0;
        for (Object[] tuple : query.getResultList()) {
            ResultRowDto r = toRow(tuple);
            csv.append(CsvUtil.row(r.attemptId(), r.candidateEmail(), r.candidateName(), r.driveCode(), r.driveName(),
                    r.status(), r.finalizedBy(), time(r.startedAt()), time(r.deadlineAt()), time(r.submittedAt()),
                    r.score(), r.maxScore(), r.correct(), r.incorrect(), r.unanswered(), r.awayCount(),
                    r.awayThresholdExceeded() ? "YES" : "NO", r.reviewStatus()));
            n++;
        }
        audit.admin(actor, "RESULTS_EXPORTED", "ExamResult", filter.driveId(), "rows=" + n
                + (filter.status() == null ? "" : " status=" + filter.status()), ip);
        return csv.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static String fromWhere(Filter f) {
        StringBuilder sb = new StringBuilder("from ExamAttempt a join a.candidate c join a.drive d "
                + "left join ExamResult r on r.attempt = a where 1 = 1");
        if (f.driveId() != null) {
            sb.append(" and d.id = :driveId");
        }
        if (f.status() != null) {
            sb.append(" and a.status = :status");
        }
        if (f.q() != null && !f.q().isBlank()) {
            sb.append(" and (lower(c.email) like :q or lower(c.fullName) like :q)");
        }
        return sb.toString();
    }

    private static void bind(TypedQuery<?> query, Filter f) {
        if (f.driveId() != null) {
            query.setParameter("driveId", f.driveId());
        }
        if (f.status() != null) {
            query.setParameter("status", f.status());
        }
        if (f.q() != null && !f.q().isBlank()) {
            query.setParameter("q", "%" + f.q().trim().toLowerCase(Locale.ROOT) + "%");
        }
    }

    private static ResultRowDto toRow(Object[] t) {
        ExamAttempt a = (ExamAttempt) t[0];
        Candidate c = (Candidate) t[1];
        RecruitmentDrive d = (RecruitmentDrive) t[2];
        ExamResult r = (ExamResult) t[3];
        long away = t[4] == null ? 0 : ((Number) t[4]).longValue();
        return new ResultRowDto(a.getId(), c.getEmail(), c.getFullName(), d.getId(), d.getCode(), d.getName(),
                a.getStatus().name(), a.getStartedAt(), a.getDeadlineAt(), a.getSubmittedAt(),
                a.getFinalizedBy() == null ? null : a.getFinalizedBy().name(),
                r == null ? null : r.getScore(), r == null ? null : r.getMaxScore(),
                r == null ? null : r.getCorrectCount(), r == null ? null : r.getIncorrectCount(),
                r == null ? null : r.getUnansweredCount(), away, away >= d.getAwayWarningThreshold(),
                a.getReviewStatus().name());
    }

    private static ResultQuestionDto toQuestion(AttemptQuestion aq) {
        Question q = aq.getQuestion();
        String outcome;
        if (aq.getSelectedOption() == null) {
            outcome = "UNANSWERED";
        } else if (aq.getSelectedOption().equals(q.getCorrectOption())) {
            outcome = "CORRECT";
        } else {
            outcome = "INCORRECT";
        }
        return new ResultQuestionDto(aq.getDisplayOrder(), q.getCode(), q.getSection().getLabel(), q.getDifficulty().name(),
                q.getText(), List.of("A. " + q.getOptionA(), "B. " + q.getOptionB(), "C. " + q.getOptionC(), "D. " + q.getOptionD()),
                q.getCorrectOption(), aq.getSelectedOption(), aq.isMarkedForReview(), outcome);
    }

    private static String time(Instant i) {
        return i == null ? "" : CSV_TIME.format(i);
    }
}

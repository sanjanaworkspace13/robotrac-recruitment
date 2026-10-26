package com.robotrac.recruitment.service;

import com.robotrac.recruitment.core.FixedWindowRateLimiter;
import com.robotrac.recruitment.core.OptionOrder;
import com.robotrac.recruitment.core.PaperAllocator;
import com.robotrac.recruitment.domain.ActorType;
import com.robotrac.recruitment.domain.AttemptQuestion;
import com.robotrac.recruitment.domain.AttemptStatus;
import com.robotrac.recruitment.domain.AuthorizationStatus;
import com.robotrac.recruitment.domain.Difficulty;
import com.robotrac.recruitment.domain.DriveAuthorization;
import com.robotrac.recruitment.domain.DrivePhase;
import com.robotrac.recruitment.domain.ExamAttempt;
import com.robotrac.recruitment.domain.FinalizedBy;
import com.robotrac.recruitment.domain.MonitoringEventType;
import com.robotrac.recruitment.domain.Question;
import com.robotrac.recruitment.domain.RecruitmentDrive;
import com.robotrac.recruitment.domain.Section;
import com.robotrac.recruitment.exception.ApiException;
import com.robotrac.recruitment.exception.AttemptClosedException;
import com.robotrac.recruitment.repository.AttemptQuestionRepository;
import com.robotrac.recruitment.repository.DriveAuthorizationRepository;
import com.robotrac.recruitment.repository.ExamAttemptRepository;
import com.robotrac.recruitment.repository.RecruitmentDriveRepository;
import com.robotrac.recruitment.security.CandidatePrincipal;
import com.robotrac.recruitment.web.dto.ExamDtos.AttemptStateDto;
import com.robotrac.recruitment.web.dto.ExamDtos.CandidateQuestionDto;
import com.robotrac.recruitment.web.dto.ExamDtos.ClockDto;
import com.robotrac.recruitment.web.dto.ExamDtos.InstructionsDto;
import com.robotrac.recruitment.web.dto.ExamDtos.QuestionStateDto;
import com.robotrac.recruitment.web.dto.ExamDtos.SubmissionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The candidate's examination. Every method identifies the attempt from the session principal (candidate +
 * drive), never from an ID supplied by the browser.
 *
 * <h2>When is the attempt consumed?</h2>
 * The moment {@link #start} succeeds. From then on the candidate can only resume that same attempt (same
 * questions, same option order, same deadline) until it is submitted or times out. Logging out, closing the
 * browser, opening another tab or repeating the request never creates a second attempt; the database
 * enforces this with UNIQUE(drive_id, candidate_id). There is no reset button. Genuine technical failures
 * are handled by HR creating a separate make-up drive for that candidate (fully audited).
 */
@Service
public class ExamService {

    private static final Logger log = LoggerFactory.getLogger(ExamService.class);
    public static final String NEUTRAL_MESSAGE = "Your examination has been submitted successfully. Your response has been recorded.";

    private final RecruitmentDriveRepository drives;
    private final DriveAuthorizationRepository authorizations;
    private final ExamAttemptRepository attempts;
    private final AttemptQuestionRepository attemptQuestions;
    private final QuestionBankService questionBank;
    private final FinalizationService finalization;
    private final MonitoringService monitoring;
    private final ExamPolicy policy;
    private final AuditService audit;
    private final FixedWindowRateLimiter limiter;
    private final SecureRandom random;
    private final Clock clock;
    private final int responsesPerMinute;

    public ExamService(RecruitmentDriveRepository drives, DriveAuthorizationRepository authorizations,
                       ExamAttemptRepository attempts, AttemptQuestionRepository attemptQuestions,
                       QuestionBankService questionBank, FinalizationService finalization, MonitoringService monitoring,
                       ExamPolicy policy, AuditService audit, FixedWindowRateLimiter limiter, SecureRandom random,
                       Clock clock, @Value("${app.rate-limit.responses-per-minute:180}") int responsesPerMinute) {
        this.drives = drives;
        this.authorizations = authorizations;
        this.attempts = attempts;
        this.attemptQuestions = attemptQuestions;
        this.questionBank = questionBank;
        this.finalization = finalization;
        this.monitoring = monitoring;
        this.policy = policy;
        this.audit = audit;
        this.limiter = limiter;
        this.random = random;
        this.clock = clock;
        this.responsesPerMinute = responsesPerMinute;
    }

    // ------------------------------------------------------------------ instructions

    @Transactional(noRollbackFor = ApiException.class)
    public InstructionsDto instructions(CandidatePrincipal p) {
        RecruitmentDrive d = drives.findById(p.driveId()).orElseThrow(() -> ApiException.notFound("Drive"));
        Instant now = clock.instant();
        String attemptStatus = currentAttemptStatus(p, now);
        long minutesLeftInWindow = Math.max(0, Duration.between(now, d.getEndAt()).toMinutes());
        long available = Math.min(d.getDurationMinutes(), minutesLeftInWindow);
        List<String> sections = Arrays.stream(Section.values()).map(Section::getLabel).toList();
        return new InstructionsDto(d.getName(), d.getCode(), d.getDescription(), d.getTotalQuestions(), d.getTotalMarks(),
                d.getDurationMinutes(), available, d.getMarksPerCorrect(), d.isNegativeMarkingEnabled(),
                d.getNegativeMarks(), sections, d.getAwayWarningThreshold(), d.getEndAt(), now, attemptStatus);
    }

    // ------------------------------------------------------------------ start / resume

    @Transactional(noRollbackFor = ApiException.class)
    public AttemptStateDto start(CandidatePrincipal p, String ip) {
        Instant now = clock.instant();
        // Lock the authorisation row: parallel start requests for the same candidate queue up here.
        DriveAuthorization auth = authorizations.findForUpdate(p.driveId(), p.candidateId())
                .orElseThrow(() -> ApiException.forbidden("NOT_AUTHORIZED", "You are not authorised for this drive."));
        if (auth.getStatus() != AuthorizationStatus.AUTHORIZED) {
            throw ApiException.forbidden("NOT_AUTHORIZED", "You are not authorised for this drive.");
        }

        ExamAttempt locked = attempts.findForUpdate(p.driveId(), p.candidateId()).orElse(null);
        if (locked != null) {
            if (locked.getStatus() == AttemptStatus.IN_PROGRESS && policy.isExpired(locked, now)) {
                finalization.finalizeLocked(locked, AttemptStatus.TIMED_OUT, FinalizedBy.SERVER_DEADLINE, now);
            }
            if (locked.getStatus().isFinal()) {
                throw new AttemptClosedException("You have already completed this assessment. Only one attempt is allowed.");
            }
            return buildState(locked, now); // idempotent: same attempt, same questions
        }

        RecruitmentDrive drive = auth.getDrive();
        requireOpenWindow(drive, now);
        Instant deadline = now.plus(Duration.ofMinutes(drive.getDurationMinutes()));
        if (deadline.isAfter(drive.getEndAt())) {
            deadline = drive.getEndAt();
        }
        if (Duration.between(now, deadline).getSeconds() < 60) {
            throw ApiException.forbidden("DRIVE_ENDED", "The test window is closing, so a new attempt cannot be started.");
        }

        List<Question> paper = drawPaper(drive);
        ExamAttempt attempt = attempts.saveAndFlush(new ExamAttempt(drive, auth.getCandidate(), now, deadline));
        List<AttemptQuestion> rows = new ArrayList<>(paper.size());
        int number = 1;
        for (Question q : paper) {
            String order = drive.isShuffleOptions() ? OptionOrder.random(random) : OptionOrder.IDENTITY;
            rows.add(new AttemptQuestion(attempt, q, number++, order));
        }
        attemptQuestions.saveAll(rows);
        monitoring.recordServerEvent(attempt, MonitoringEventType.ATTEMPT_STARTED, "Attempt started from " + ip);
        audit.record(ActorType.CANDIDATE, p.candidateId(), p.email(), "ATTEMPT_STARTED", "ExamAttempt",
                attempt.getId(), "drive=" + drive.getCode() + " questions=" + rows.size() + " deadline=" + deadline, ip);
        return buildState(attempt, rows, now);
    }

    /** Draws the questions for one candidate following the drive's difficulty mix and section policy. */
    private List<Question> drawPaper(RecruitmentDrive drive) {
        Map<Section, Map<Difficulty, List<Question>>> pool = questionBank.eligibleByCell();
        int[][] available = new int[Section.values().length][Difficulty.values().length];
        for (Section s : Section.values()) {
            for (Difficulty d : Difficulty.values()) {
                available[s.ordinal()][d.ordinal()] = pool.get(s).get(d).size();
            }
        }
        int[] need = new int[Difficulty.values().length];
        need[Difficulty.EASY.ordinal()] = drive.getEasyCount();
        need[Difficulty.MODERATE.ordinal()] = drive.getModerateCount();
        need[Difficulty.HARD.ordinal()] = drive.getHardCount();
        int[][] plan;
        try {
            plan = PaperAllocator.allocate(available, need, drive.isSectionBalanced(), random);
        } catch (PaperAllocator.AllocationException e) {
            log.error("Cannot build a paper for drive {}: {}", drive.getCode(), e.getMessage());
            audit.record(ActorType.SYSTEM, null, null, "PAPER_GENERATION_FAILED", "RecruitmentDrive", drive.getId(),
                    e.getMessage(), null);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAPER_UNAVAILABLE",
                    "The test cannot be prepared right now. Please contact HR. (Your attempt has not been used.)");
        }
        List<Question> paper = new ArrayList<>();
        for (Section s : Section.values()) {
            List<Question> sectionQuestions = new ArrayList<>();
            for (Difficulty d : Difficulty.values()) {
                List<Question> cell = new ArrayList<>(pool.get(s).get(d));
                Collections.shuffle(cell, random);
                sectionQuestions.addAll(cell.subList(0, plan[s.ordinal()][d.ordinal()]));
            }
            Collections.shuffle(sectionQuestions, random); // mixed difficulty within each section
            paper.addAll(sectionQuestions);
        }
        return paper;
    }

    // ------------------------------------------------------------------ state

    @Transactional(noRollbackFor = ApiException.class)
    public AttemptStateDto state(CandidatePrincipal p) {
        Instant now = clock.instant();
        ExamAttempt attempt = attempts.findForUpdate(p.driveId(), p.candidateId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_ATTEMPT", "You have not started this test yet."));
        if (attempt.getStatus() == AttemptStatus.IN_PROGRESS && policy.isExpired(attempt, now)) {
            finalization.finalizeLocked(attempt, AttemptStatus.TIMED_OUT, FinalizedBy.SERVER_DEADLINE, now);
        }
        if (attempt.getStatus().isFinal()) {
            return completedState(attempt, now);
        }
        return buildState(attempt, now);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public ClockDto clock(CandidatePrincipal p) {
        AttemptStateDto s = state(p);
        Instant now = s.serverNow();
        return new ClockDto(s.status(), now, s.deadlineAt(), s.remainingSeconds() == null ? 0 : s.remainingSeconds());
    }

    // ------------------------------------------------------------------ responses

    @Transactional(noRollbackFor = ApiException.class)
    public QuestionStateDto saveAnswer(CandidatePrincipal p, int number, int selectedIndex, boolean markedForReview, long seq) {
        throttle(p);
        Instant now = clock.instant();
        ExamAttempt attempt = lockOpenAttempt(p, now);
        AttemptQuestion aq = question(attempt, number);
        requireNewer(aq, seq, attempt, now);
        aq.setSelectedOption(String.valueOf(OptionOrder.letterAt(aq.getOptionOrder(), selectedIndex)));
        aq.setMarkedForReview(markedForReview);
        aq.setVisited(true);
        aq.setLastSeq(seq);
        aq.setAnsweredAt(now);
        aq.setUpdatedAt(now);
        return toState(aq, attempt, now);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public QuestionStateDto clearAnswer(CandidatePrincipal p, int number, long seq) {
        throttle(p);
        Instant now = clock.instant();
        ExamAttempt attempt = lockOpenAttempt(p, now);
        AttemptQuestion aq = question(attempt, number);
        requireNewer(aq, seq, attempt, now);
        aq.setSelectedOption(null);
        aq.setAnsweredAt(null);
        aq.setVisited(true);
        aq.setLastSeq(seq);
        aq.setUpdatedAt(now);
        return toState(aq, attempt, now);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public QuestionStateDto setReview(CandidatePrincipal p, int number, boolean marked, long seq) {
        throttle(p);
        Instant now = clock.instant();
        ExamAttempt attempt = lockOpenAttempt(p, now);
        AttemptQuestion aq = question(attempt, number);
        requireNewer(aq, seq, attempt, now);
        aq.setMarkedForReview(marked);
        aq.setVisited(true);
        aq.setLastSeq(seq);
        aq.setUpdatedAt(now);
        return toState(aq, attempt, now);
    }

    /** Marks a question as seen (idempotent; does not change the answer). */
    @Transactional(noRollbackFor = ApiException.class)
    public QuestionStateDto visit(CandidatePrincipal p, int number) {
        throttle(p);
        Instant now = clock.instant();
        ExamAttempt attempt = lockOpenAttempt(p, now);
        AttemptQuestion aq = question(attempt, number);
        if (!aq.isVisited()) {
            aq.setVisited(true);
            aq.setUpdatedAt(now);
        }
        return toState(aq, attempt, now);
    }

    // ------------------------------------------------------------------ submit

    @Transactional(noRollbackFor = ApiException.class)
    public SubmissionDto submit(CandidatePrincipal p, String reason, String ip) {
        Instant now = clock.instant();
        ExamAttempt attempt = attempts.findForUpdate(p.driveId(), p.candidateId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_ATTEMPT", "You have not started this test yet."));
        if (attempt.getStatus() == AttemptStatus.IN_PROGRESS) {
            boolean timer = "TIMER".equals(reason);
            if (policy.isExpired(attempt, now)) {
                finalization.finalizeLocked(attempt, AttemptStatus.TIMED_OUT, FinalizedBy.SERVER_DEADLINE, now);
            } else if (timer) {
                boolean atDeadline = !now.isBefore(attempt.getDeadlineAt().minusSeconds(5));
                finalization.finalizeLocked(attempt, atDeadline ? AttemptStatus.TIMED_OUT : AttemptStatus.SUBMITTED,
                        FinalizedBy.CLIENT_TIMER, now);
            } else {
                finalization.finalizeLocked(attempt, AttemptStatus.SUBMITTED, FinalizedBy.CANDIDATE, now);
            }
        }
        // Same neutral answer whether this call or an earlier one closed the attempt (idempotent).
        return new SubmissionDto("RECORDED", NEUTRAL_MESSAGE, attempt.getSubmittedAt());
    }

    // ------------------------------------------------------------------ helpers

    /** Status string for the instructions page: null (not started), IN_PROGRESS or COMPLETED. */
    private String currentAttemptStatus(CandidatePrincipal p, Instant now) {
        ExamAttempt a = attempts.findForUpdate(p.driveId(), p.candidateId()).orElse(null);
        if (a == null) {
            return null;
        }
        if (a.getStatus() == AttemptStatus.IN_PROGRESS && policy.isExpired(a, now)) {
            finalization.finalizeLocked(a, AttemptStatus.TIMED_OUT, FinalizedBy.SERVER_DEADLINE, now);
            return "COMPLETED";
        }
        return a.getStatus().isFinal() ? "COMPLETED" : "IN_PROGRESS";
    }

    static void requireOpenWindow(RecruitmentDrive drive, Instant now) {
        DrivePhase phase = drive.phaseAt(now);
        switch (phase) {
            case LIVE -> {
                // ok
            }
            case SCHEDULED -> throw new ApiException(HttpStatus.FORBIDDEN, "DRIVE_NOT_STARTED",
                    "This test has not opened yet.", Map.of("startAt", drive.getStartAt().toString()));
            case ENDED -> throw ApiException.forbidden("DRIVE_ENDED", "The time window for this test has ended.");
            default -> throw ApiException.forbidden("DRIVE_NOT_ACTIVE", "This recruitment drive is not open.");
        }
    }

    private ExamAttempt lockOpenAttempt(CandidatePrincipal p, Instant now) {
        ExamAttempt attempt = attempts.findForUpdate(p.driveId(), p.candidateId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_ATTEMPT", "You have not started this test yet."));
        if (attempt.getStatus().isFinal()) {
            throw new AttemptClosedException("Your test has already been submitted.");
        }
        if (policy.isExpired(attempt, now)) {
            finalization.finalizeLocked(attempt, AttemptStatus.TIMED_OUT, FinalizedBy.SERVER_DEADLINE, now);
            throw new AttemptClosedException("Time is up. Your saved answers have been submitted.");
        }
        return attempt;
    }

    private AttemptQuestion question(ExamAttempt attempt, int number) {
        return attemptQuestions.findByAttemptIdAndDisplayOrder(attempt.getId(), number)
                .orElseThrow(() -> ApiException.notFound("Question " + number));
    }

    /** Rejects out-of-order updates (e.g. a slow retry arriving after a newer click). */
    private void requireNewer(AttemptQuestion aq, long seq, ExamAttempt attempt, Instant now) {
        if (seq <= aq.getLastSeq()) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_UPDATE",
                    "A newer change to this question was already saved.", toState(aq, attempt, now));
        }
    }

    private void throttle(CandidatePrincipal p) {
        if (!limiter.tryAcquire("responses:" + p.candidateId() + ":" + p.driveId(), responsesPerMinute, Duration.ofMinutes(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "You are sending changes too quickly. Please wait a moment.");
        }
    }

    private QuestionStateDto toState(AttemptQuestion aq, ExamAttempt attempt, Instant now) {
        return new QuestionStateDto(aq.getDisplayOrder(),
                OptionOrder.displayIndexOf(aq.getOptionOrder(), aq.getSelectedOption()),
                aq.isMarkedForReview(), aq.isVisited(), aq.getLastSeq(), aq.getUpdatedAt(), now,
                policy.remainingSeconds(attempt, now));
    }

    private AttemptStateDto buildState(ExamAttempt attempt, Instant now) {
        return buildState(attempt, attemptQuestions.findByAttemptWithQuestion(attempt.getId()), now);
    }

    private AttemptStateDto buildState(ExamAttempt attempt, List<AttemptQuestion> rows, Instant now) {
        List<CandidateQuestionDto> questions = new ArrayList<>(rows.size());
        for (AttemptQuestion aq : rows) {
            Question q = aq.getQuestion();
            String order = aq.getOptionOrder();
            List<String> options = List.of(q.optionText(order.charAt(0)), q.optionText(order.charAt(1)),
                    q.optionText(order.charAt(2)), q.optionText(order.charAt(3)));
            questions.add(new CandidateQuestionDto(aq.getDisplayOrder(), q.getSection().getLabel(), q.getText(), options,
                    OptionOrder.displayIndexOf(order, aq.getSelectedOption()), aq.isMarkedForReview(), aq.isVisited(),
                    aq.getLastSeq()));
        }
        RecruitmentDrive drive = attempt.getDrive();
        return new AttemptStateDto(attempt.getStatus().name(), drive.getName(), attempt.getCandidate().getEmail(), now,
                attempt.getStartedAt(), attempt.getDeadlineAt(), policy.remainingSeconds(attempt, now), questions.size(),
                monitoring.awayCount(attempt.getId()), drive.getAwayWarningThreshold(), questions, null);
    }

    private AttemptStateDto completedState(ExamAttempt attempt, Instant now) {
        return new AttemptStateDto("COMPLETED", attempt.getDrive().getName(), attempt.getCandidate().getEmail(), now,
                null, null, 0L, null, null, null, null, NEUTRAL_MESSAGE);
    }
}

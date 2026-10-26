package com.robotrac.recruitment.service;

import com.robotrac.recruitment.domain.AttemptStatus;
import com.robotrac.recruitment.domain.AuthorizationStatus;
import com.robotrac.recruitment.domain.DrivePhase;
import com.robotrac.recruitment.domain.DriveStatus;
import com.robotrac.recruitment.domain.QuestionReviewStatus;
import com.robotrac.recruitment.repository.DriveAuthorizationRepository;
import com.robotrac.recruitment.repository.ExamAttemptRepository;
import com.robotrac.recruitment.repository.QuestionRepository;
import com.robotrac.recruitment.repository.RecruitmentDriveRepository;
import com.robotrac.recruitment.web.dto.DriveDtos.DriveDto;
import com.robotrac.recruitment.web.dto.ResultDtos.DashboardStats;
import com.robotrac.recruitment.web.dto.ResultDtos.DriveSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Real counts for the HR overview (no sample or placeholder numbers). */
@Service
public class DashboardService {

    private final RecruitmentDriveRepository drives;
    private final DriveAuthorizationRepository authorizations;
    private final ExamAttemptRepository attempts;
    private final QuestionRepository questions;
    private final QuestionBankService questionBank;
    private final DriveService driveService;
    private final Clock clock;

    public DashboardService(RecruitmentDriveRepository drives, DriveAuthorizationRepository authorizations,
                            ExamAttemptRepository attempts, QuestionRepository questions,
                            QuestionBankService questionBank, DriveService driveService, Clock clock) {
        this.drives = drives;
        this.authorizations = authorizations;
        this.attempts = attempts;
        this.questions = questions;
        this.questionBank = questionBank;
        this.driveService = driveService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardStats stats() {
        Instant now = clock.instant();
        List<DriveDto> all = driveService.list();
        long live = all.stream().filter(d -> DrivePhase.LIVE.name().equals(d.phase())).count();
        long authorized = authorizations.countByStatus(AuthorizationStatus.AUTHORIZED);
        long inProgress = attempts.countByStatus(AttemptStatus.IN_PROGRESS);
        long submitted = attempts.countByStatus(AttemptStatus.SUBMITTED);
        long timedOut = attempts.countByStatus(AttemptStatus.TIMED_OUT);
        long started = inProgress + submitted + timedOut;

        List<DriveSummary> summaries = all.stream().map(d -> new DriveSummary(d.id(), d.code(), d.name(), d.phase(),
                d.authorizedCount(), d.attemptsStarted(), d.attemptsCompleted(), d.attemptsInProgress(),
                Math.max(0, d.authorizedCount() - d.attemptsStarted()))).toList();
        long notStarted = summaries.stream().mapToLong(DriveSummary::notStarted).sum();
        long eligible = 0;
        for (int[] row : questionBank.eligibleMatrix()) {
            for (int v : row) {
                eligible += v;
            }
        }
        return new DashboardStats(drives.count(), drives.countByStatus(DriveStatus.ACTIVE), live, authorized,
                started, submitted, timedOut, inProgress, notStarted, eligible,
                questions.countByActiveTrueAndReviewStatus(QuestionReviewStatus.FLAGGED), summaries, now);
    }
}

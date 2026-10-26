package com.robotrac.recruitment.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** HR-only result, monitoring, dashboard and audit bodies. */
public final class ResultDtos {

    private ResultDtos() {
    }

    public record ResultRowDto(Long attemptId, String candidateEmail, String candidateName, Long driveId,
                               String driveCode, String driveName, String status, Instant startedAt,
                               Instant deadlineAt, Instant submittedAt, String finalizedBy, BigDecimal score,
                               BigDecimal maxScore, Integer correct, Integer incorrect, Integer unanswered,
                               long awayCount, boolean awayThresholdExceeded, String reviewStatus) {
    }

    public record ResultQuestionDto(int number, String code, String section, String difficulty, String text,
                                    List<String> options, String correctOption, String selectedOption,
                                    boolean markedForReview, String outcome) {
    }

    public record EventDto(Long id, String type, String description, String source, boolean away,
                           Instant serverTime, Instant clientTime, String detail) {
    }

    public record ResultDetailDto(ResultRowDto summary, String reviewNotes, int awayWarningThreshold,
                                  Map<String, Long> eventCounts, List<EventDto> events,
                                  List<ResultQuestionDto> questions) {
    }

    public record AttemptReviewRequest(
            @NotBlank @Pattern(regexp = "NOT_REVIEWED|REVIEWED_NO_CONCERN|NEEDS_FOLLOW_UP") String reviewStatus,
            @Size(max = 2000) String notes) {
    }

    public record DriveSummary(Long id, String code, String name, String phase, long authorized, long started,
                               long completed, long inProgress, long notStarted) {
    }

    public record DashboardStats(long totalDrives, long activeDrives, long liveDrives, long authorizedCandidates,
                                 long attemptsStarted, long submitted, long timedOut, long inProgress,
                                 long notStarted, long questionsEligible, long questionsFlagged,
                                 List<DriveSummary> drives, Instant serverNow) {
    }

    public record AuditLogDto(Long id, Instant createdAt, String actorType, String actorName, String action,
                              String entityType, String entityId, String details, String ipAddress) {
    }
}

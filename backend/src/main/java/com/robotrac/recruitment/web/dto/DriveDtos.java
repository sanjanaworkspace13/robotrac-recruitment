package com.robotrac.recruitment.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** HR bodies for drives and candidate authorisation. */
public final class DriveDtos {

    private DriveDtos() {
    }

    public record DriveRequest(
            @NotBlank(message = "Drive name is required.") @Size(max = 150) String name,
            @NotBlank(message = "Drive code is required.") @Size(max = 20) String code,
            @Size(max = 1000) String description,
            @NotNull(message = "Start date and time are required.") Instant startAt,
            @NotNull(message = "End date and time are required.") Instant endAt,
            @NotNull @Min(value = 5, message = "Duration must be at least 5 minutes.")
            @Max(value = 300, message = "Duration must be at most 300 minutes.") Integer durationMinutes,
            @NotNull @Min(0) @Max(100) Integer easyCount,
            @NotNull @Min(0) @Max(100) Integer moderateCount,
            @NotNull @Min(0) @Max(100) Integer hardCount,
            Boolean sectionBalanced,
            Boolean shuffleOptions,
            @NotNull @DecimalMin(value = "0.25") @DecimalMax(value = "10") BigDecimal marksPerCorrect,
            Boolean negativeMarkingEnabled,
            @DecimalMin(value = "0") @DecimalMax(value = "10") BigDecimal negativeMarks,
            @Min(1) @Max(50) Integer awayWarningThreshold,
            @Size(max = 64) String drivePassword) {
    }

    public record DrivePasswordRequest(@NotBlank(message = "Drive password is required.") @Size(max = 64) String drivePassword) {
    }

    public record DriveDto(Long id, String name, String code, String description, Instant startAt, Instant endAt,
                           String status, String phase, int durationMinutes, int easyCount, int moderateCount,
                           int hardCount, int totalQuestions, boolean sectionBalanced, boolean shuffleOptions,
                           BigDecimal marksPerCorrect, BigDecimal totalMarks, boolean negativeMarkingEnabled,
                           BigDecimal negativeMarks, int awayWarningThreshold, boolean passwordSet,
                           String createdBy, Instant createdAt, Instant updatedAt, Instant activatedAt,
                           Instant closedAt, long authorizedCount, long attemptsStarted, long attemptsCompleted,
                           long attemptsInProgress) {
    }

    public record CellDto(String section, String sectionLabel, String difficulty, long available, Integer planned) {
    }

    public record AvailabilityDto(boolean feasible, List<String> problems, List<CellDto> cells,
                                  long totalEligible, int requested, long needsReviewInPool) {
    }

    public record ActivationResultDto(DriveDto drive, List<String> warnings) {
    }

    public record AddCandidateRequest(
            @NotBlank(message = "Email is required.") @Size(max = 254) String email,
            @Size(max = 120) String fullName) {
    }

    public record BulkCandidatesRequest(
            @NotBlank(message = "Paste at least one email.") @Size(max = 200_000) String text) {
    }

    public record LineError(int line, String value, String reason) {
    }

    public record CandidateImportReport(int added, int reactivated, int alreadyAuthorized, List<LineError> invalid) {
    }

    public record AuthorizedCandidateDto(Long authorizationId, Long candidateId, String email, String fullName,
                                         String status, Instant addedAt, Instant revokedAt, Long attemptId,
                                         String attemptStatus) {
    }
}

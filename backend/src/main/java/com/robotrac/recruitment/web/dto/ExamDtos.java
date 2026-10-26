package com.robotrac.recruitment.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Candidate-facing bodies. By design NONE of these types has a field for the answer key, explanation,
 * difficulty, original option letters, score or result.
 */
public final class ExamDtos {

    private ExamDtos() {
    }

    public record InstructionsDto(String driveName, String driveCode, String description,
                                  int totalQuestions, BigDecimal totalMarks, int durationMinutes,
                                  long availableMinutes, BigDecimal marksPerCorrect,
                                  boolean negativeMarkingEnabled, BigDecimal negativeMarks,
                                  List<String> sections, int awayWarningThreshold,
                                  Instant windowEndsAt, Instant serverNow, String attemptStatus) {
    }

    /** One question as the candidate sees it: display options only. */
    public record CandidateQuestionDto(int number, String section, String text, List<String> options,
                                       Integer selectedIndex, boolean markedForReview, boolean visited,
                                       long lastSeq) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AttemptStateDto(String status, String driveName, String candidateEmail, Instant serverNow,
                                  Instant startedAt, Instant deadlineAt, Long remainingSeconds,
                                  Integer totalQuestions, Long awayCount, Integer awayWarningThreshold,
                                  List<CandidateQuestionDto> questions, String message) {
    }

    public record SaveResponseRequest(
            @NotNull(message = "Select an option before saving.") @Min(0) @Max(3) Integer selectedIndex,
            boolean markedForReview,
            @NotNull @Min(1) Long seq) {
    }

    public record SeqRequest(@NotNull @Min(1) Long seq) {
    }

    public record ReviewRequest(boolean markedForReview, @NotNull @Min(1) Long seq) {
    }

    public record QuestionStateDto(int number, Integer selectedIndex, boolean markedForReview, boolean visited,
                                   long lastSeq, Instant savedAt, Instant serverNow, long remainingSeconds) {
    }

    public record SubmitRequest(@Pattern(regexp = "MANUAL|TIMER") String reason) {
    }

    /** Neutral confirmation - deliberately no score, status detail or outcome. */
    public record SubmissionDto(String status, String message, Instant recordedAt) {
    }

    public record ClockDto(String status, Instant serverNow, Instant deadlineAt, long remainingSeconds) {
    }

    public record MonitoringEventRequest(
            @NotBlank @Size(max = 30) String type,
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9-]{8,64}$") String clientEventId,
            Instant clientTime,
            @Size(max = 200) String detail) {
    }

    public record MonitoringAckDto(boolean recorded, boolean duplicate, long awayCount, int warningThreshold) {
    }
}

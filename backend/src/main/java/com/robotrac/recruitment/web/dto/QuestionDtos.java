package com.robotrac.recruitment.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** HR bodies for the question bank. These include answer keys and are served only under /api/admin. */
public final class QuestionDtos {

    private QuestionDtos() {
    }

    public record QuestionAdminDto(Long id, String code, String section, String sectionLabel, String difficulty,
                                   String text, String optionA, String optionB, String optionC, String optionD,
                                   String correctOption, String explanation, String reviewStatus,
                                   String validationNotes, boolean active, boolean usedInAttempts, Integer sourceRow) {
    }

    public record QuestionUpdateRequest(
            @NotBlank @Size(max = 2000) String text,
            @NotBlank @Size(max = 500) String optionA,
            @NotBlank @Size(max = 500) String optionB,
            @NotBlank @Size(max = 500) String optionC,
            @NotBlank @Size(max = 500) String optionD,
            @NotBlank @Pattern(regexp = "[ABCD]", message = "Correct option must be A, B, C or D.") String correctOption,
            @Size(max = 2000) String explanation) {
    }

    public record QuestionReviewRequest(
            @NotBlank @Pattern(regexp = "NEEDS_REVIEW|APPROVED|FLAGGED") String reviewStatus,
            @Size(max = 500) String note) {
    }

    public record QuestionActiveRequest(@NotNull Boolean active) {
    }

    public record CellCount(String section, String sectionLabel, String difficulty, long eligible) {
    }

    public record QuestionBankSummary(long total, long eligible, long needsReview, long approved, long flagged,
                                      long inactive, List<CellCount> cells) {
    }

    public record RowReport(int row, String code, String outcome, List<String> messages) {
    }

    public record ImportReport(String fileName, int totalRows, int imported, int flagged, int rejected,
                               int skippedExisting, List<RowReport> rows) {
    }
}

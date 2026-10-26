package com.robotrac.recruitment.domain;

/**
 * NEEDS_REVIEW - imported, usable, but not yet signed off by an SME (the workbook marks all 150 this way).
 * APPROVED     - signed off by HR/SME.
 * FLAGGED      - has a data problem or was flagged by HR; never placed in a paper.
 */
public enum QuestionReviewStatus {
    NEEDS_REVIEW, APPROVED, FLAGGED
}

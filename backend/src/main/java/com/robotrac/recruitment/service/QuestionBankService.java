package com.robotrac.recruitment.service;

import com.robotrac.recruitment.core.QuestionRowValidator;
import com.robotrac.recruitment.core.QuestionRowValidator.Outcome;
import com.robotrac.recruitment.core.QuestionRowValidator.RawRow;
import com.robotrac.recruitment.core.QuestionRowValidator.ValidatedRow;
import com.robotrac.recruitment.domain.ActorType;
import com.robotrac.recruitment.domain.Difficulty;
import com.robotrac.recruitment.domain.Question;
import com.robotrac.recruitment.domain.QuestionReviewStatus;
import com.robotrac.recruitment.domain.Section;
import com.robotrac.recruitment.exception.ApiException;
import com.robotrac.recruitment.repository.AttemptQuestionRepository;
import com.robotrac.recruitment.repository.QuestionRepository;
import com.robotrac.recruitment.security.AdminPrincipal;
import com.robotrac.recruitment.web.dto.PageResponse;
import com.robotrac.recruitment.web.dto.QuestionDtos.CellCount;
import com.robotrac.recruitment.web.dto.QuestionDtos.ImportReport;
import com.robotrac.recruitment.web.dto.QuestionDtos.QuestionAdminDto;
import com.robotrac.recruitment.web.dto.QuestionDtos.QuestionBankSummary;
import com.robotrac.recruitment.web.dto.QuestionDtos.QuestionReviewRequest;
import com.robotrac.recruitment.web.dto.QuestionDtos.QuestionUpdateRequest;
import com.robotrac.recruitment.web.dto.QuestionDtos.RowReport;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Question bank: validated import from the HR workbook, HR review/approval, and the eligibility data used
 * to build papers. The answer key never leaves this service except in HR-only DTOs.
 */
@Service
public class QuestionBankService {

    private final QuestionRepository questions;
    private final AttemptQuestionRepository attemptQuestions;
    private final WorkbookReader workbookReader;
    private final AuditService audit;
    private final Clock clock;

    public QuestionBankService(QuestionRepository questions, AttemptQuestionRepository attemptQuestions,
                               WorkbookReader workbookReader, AuditService audit, Clock clock) {
        this.questions = questions;
        this.attemptQuestions = attemptQuestions;
        this.workbookReader = workbookReader;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Imports a workbook. New Question IDs are inserted; IDs that already exist are left unchanged and
     * reported as SKIPPED_EXISTING (so re-importing the same file is safe and never alters answer keys of
     * questions that candidates may already have answered).
     */
    @Transactional
    public ImportReport importWorkbook(InputStream input, String fileName, ActorType actorType, Long actorId,
                                       String actorName, String ip) {
        WorkbookReader.ParsedWorkbook parsed = workbookReader.read(input);
        Set<String> existingCodes = questions.findAllCodes().stream()
                .map(c -> c.toUpperCase(Locale.ROOT)).collect(Collectors.toCollection(HashSet::new));
        Set<String> existingTexts = questions.findAllTexts().stream()
                .map(QuestionRowValidator::textKey).collect(Collectors.toCollection(HashSet::new));

        List<ValidatedRow> validated = QuestionRowValidator.validate(parsed.rows(), existingTexts);
        Instant now = clock.instant();
        int imported = 0;
        int flagged = 0;
        int rejected = 0;
        int skipped = 0;
        List<RowReport> report = new ArrayList<>();

        for (ValidatedRow v : validated) {
            RawRow raw = v.raw();
            String code = QuestionRowValidator.clean(raw.code());
            if (v.outcome() == Outcome.REJECTED) {
                rejected++;
                report.add(new RowReport(raw.rowNumber(), code, "REJECTED", v.issues()));
                continue;
            }
            if (existingCodes.contains(code.toUpperCase(Locale.ROOT))) {
                skipped++;
                report.add(new RowReport(raw.rowNumber(), code, "SKIPPED_EXISTING",
                        List.of("A question with this ID is already in the bank; it was left unchanged.")));
                continue;
            }
            Question q = new Question();
            q.setCode(code);
            q.setSection(v.section());
            q.setDifficulty(v.difficulty());
            q.setText(QuestionRowValidator.clean(raw.text()));
            q.setOptionA(QuestionRowValidator.clean(raw.optionA()));
            q.setOptionB(QuestionRowValidator.clean(raw.optionB()));
            q.setOptionC(QuestionRowValidator.clean(raw.optionC()));
            q.setOptionD(QuestionRowValidator.clean(raw.optionD()));
            q.setCorrectOption(v.correctOption());
            q.setExplanation(QuestionRowValidator.clean(raw.explanation()));
            q.setSourceMarks(v.marks());
            q.setReviewStatus(v.reviewStatus());
            q.setValidationNotes(notes(v.issues(), v.warnings()));
            q.setActive(true);
            q.setSourceRow(raw.rowNumber());
            q.setCreatedAt(now);
            q.setUpdatedAt(now);
            questions.save(q);
            existingCodes.add(code.toUpperCase(Locale.ROOT));
            if (v.outcome() == Outcome.FLAGGED) {
                flagged++;
                report.add(new RowReport(raw.rowNumber(), code, "FLAGGED", merge(v.issues(), v.warnings())));
            } else {
                imported++;
                if (!v.warnings().isEmpty()) {
                    report.add(new RowReport(raw.rowNumber(), code, "IMPORTED", v.warnings()));
                }
            }
        }

        String summary = String.format("file=%s sheet=%s rows=%d imported=%d flagged=%d rejected=%d skipped=%d",
                fileName, parsed.sheetName(), parsed.rows().size(), imported, flagged, rejected, skipped);
        String problemRows = report.stream().filter(r -> !r.outcome().equals("IMPORTED") && !r.outcome().equals("SKIPPED_EXISTING"))
                .map(r -> "row " + r.row() + " " + r.code() + " " + r.outcome() + ": " + String.join(" ", r.messages()))
                .collect(Collectors.joining(" | "));
        audit.record(actorType, actorId, actorName, "QUESTIONS_IMPORTED", "QuestionBank", fileName,
                summary + (problemRows.isEmpty() ? "" : " | " + problemRows), ip);
        return new ImportReport(fileName, parsed.rows().size(), imported, flagged, rejected, skipped, report);
    }

    @Transactional(readOnly = true)
    public QuestionBankSummary summary() {
        int[][] matrix = eligibleMatrix();
        List<CellCount> cells = new ArrayList<>();
        long eligible = 0;
        for (Section s : Section.values()) {
            for (Difficulty d : Difficulty.values()) {
                int n = matrix[s.ordinal()][d.ordinal()];
                eligible += n;
                cells.add(new CellCount(s.name(), s.getLabel(), d.name(), n));
            }
        }
        long total = questions.count();
        long inactive = total - questions.countByActiveTrueAndReviewStatus(QuestionReviewStatus.NEEDS_REVIEW)
                - questions.countByActiveTrueAndReviewStatus(QuestionReviewStatus.APPROVED)
                - questions.countByActiveTrueAndReviewStatus(QuestionReviewStatus.FLAGGED);
        return new QuestionBankSummary(total, eligible,
                questions.countByActiveTrueAndReviewStatus(QuestionReviewStatus.NEEDS_REVIEW),
                questions.countByActiveTrueAndReviewStatus(QuestionReviewStatus.APPROVED),
                questions.countByActiveTrueAndReviewStatus(QuestionReviewStatus.FLAGGED),
                inactive, cells);
    }

    /** eligible[section][difficulty] counts. */
    @Transactional(readOnly = true)
    public int[][] eligibleMatrix() {
        int[][] m = new int[Section.values().length][Difficulty.values().length];
        for (Object[] row : questions.countEligibleByCell(QuestionReviewStatus.FLAGGED)) {
            Section s = (Section) row[0];
            Difficulty d = (Difficulty) row[1];
            m[s.ordinal()][d.ordinal()] = ((Number) row[2]).intValue();
        }
        return m;
    }

    /** Eligible questions grouped by cell, for drawing a paper. */
    @Transactional(readOnly = true)
    public Map<Section, Map<Difficulty, List<Question>>> eligibleByCell() {
        Map<Section, Map<Difficulty, List<Question>>> map = new EnumMap<>(Section.class);
        for (Section s : Section.values()) {
            Map<Difficulty, List<Question>> inner = new EnumMap<>(Difficulty.class);
            for (Difficulty d : Difficulty.values()) {
                inner.put(d, new ArrayList<>());
            }
            map.put(s, inner);
        }
        for (Question q : questions.findEligible(QuestionReviewStatus.FLAGGED)) {
            map.get(q.getSection()).get(q.getDifficulty()).add(q);
        }
        return map;
    }

    @Transactional(readOnly = true)
    public long countEligibleNeedingReview() {
        return questions.findEligible(QuestionReviewStatus.FLAGGED).stream()
                .filter(q -> q.getReviewStatus() == QuestionReviewStatus.NEEDS_REVIEW).count();
    }

    @Transactional(readOnly = true)
    public PageResponse<QuestionAdminDto> search(String section, String difficulty, String status, String q,
                                                 int page, int size) {
        Section s = parseEnum(Section.class, section);
        Difficulty d = parseEnum(Difficulty.class, difficulty);
        QuestionReviewStatus st = parseEnum(QuestionReviewStatus.class, status);
        String like = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return PageResponse.of(questions.search(s, d, st, like,
                        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by("code"))),
                this::toDto);
    }

    @Transactional(readOnly = true)
    public QuestionAdminDto get(Long id) {
        return toDto(find(id));
    }

    /**
     * HR correction of a question's wording/options/key. Allowed only while the question has never been
     * placed in any attempt, so existing papers and scores can never change retroactively.
     */
    @Transactional
    public QuestionAdminDto update(Long id, QuestionUpdateRequest req, AdminPrincipal actor, String ip) {
        Question q = find(id);
        if (attemptQuestions.existsByQuestionId(id)) {
            throw ApiException.conflict("QUESTION_IN_USE",
                    "This question has already been used in an exam attempt, so it cannot be edited. "
                            + "Deactivate it and import a corrected copy with a new Question ID instead.");
        }
        RawRow raw = new RawRow(q.getSourceRow() == null ? 0 : q.getSourceRow(), q.getCode(), q.getSection().getLabel(),
                q.getDifficulty().name(), req.text(), req.optionA(), req.optionB(), req.optionC(), req.optionD(),
                req.correctOption(), null, req.explanation(), null, q.getReviewStatus().name());
        Set<String> otherTexts = new HashSet<>();
        for (String t : questions.findAllTexts()) {
            otherTexts.add(QuestionRowValidator.textKey(t));
        }
        otherTexts.remove(QuestionRowValidator.textKey(q.getText()));
        ValidatedRow v = QuestionRowValidator.validate(List.of(raw), otherTexts).get(0);
        if (v.outcome() == Outcome.REJECTED) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_QUESTION",
                    String.join(" ", v.issues()), v.issues());
        }
        q.setText(req.text().strip());
        q.setOptionA(req.optionA().strip());
        q.setOptionB(req.optionB().strip());
        q.setOptionC(req.optionC().strip());
        q.setOptionD(req.optionD().strip());
        q.setCorrectOption(v.correctOption());
        q.setExplanation(QuestionRowValidator.clean(req.explanation()));
        if (v.outcome() == Outcome.FLAGGED) {
            q.setReviewStatus(QuestionReviewStatus.FLAGGED);
            q.setValidationNotes(notes(v.issues(), v.warnings()));
        } else {
            // Edited and now valid: back to NEEDS_REVIEW so HR explicitly approves the new wording.
            q.setReviewStatus(QuestionReviewStatus.NEEDS_REVIEW);
            q.setValidationNotes("Edited by " + actor.username() + "; awaiting review.");
        }
        q.setUpdatedAt(clock.instant());
        audit.admin(actor, "QUESTION_EDITED", "Question", q.getCode(), "status=" + q.getReviewStatus(), ip);
        return toDto(q);
    }

    @Transactional
    public QuestionAdminDto setReviewStatus(Long id, QuestionReviewRequest req, AdminPrincipal actor, String ip) {
        Question q = find(id);
        QuestionReviewStatus target = QuestionReviewStatus.valueOf(req.reviewStatus());
        if (target != QuestionReviewStatus.FLAGGED && q.getCorrectOption() == null) {
            throw ApiException.conflict("NO_ANSWER_KEY",
                    "This question has no valid answer key. Edit it to set the correct option first.");
        }
        if (target != QuestionReviewStatus.FLAGGED) {
            // Re-check the data before un-flagging so a broken question cannot be approved by accident.
            RawRow raw = new RawRow(0, q.getCode(), q.getSection().getLabel(), q.getDifficulty().name(), q.getText(),
                    q.getOptionA(), q.getOptionB(), q.getOptionC(), q.getOptionD(), q.getCorrectOption(), null,
                    q.getExplanation(), null, null);
            ValidatedRow v = QuestionRowValidator.validate(List.of(raw), Set.of()).get(0);
            if (v.outcome() != Outcome.ACCEPTED) {
                throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "QUESTION_HAS_ISSUES",
                        "Fix this question before approving it: " + String.join(" ", v.issues()), v.issues());
            }
        }
        QuestionReviewStatus before = q.getReviewStatus();
        q.setReviewStatus(target);
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        if (target == QuestionReviewStatus.FLAGGED && note != null) {
            q.setValidationNotes(truncate("Flagged by " + actor.username() + ": " + note, 1000));
        } else if (target == QuestionReviewStatus.APPROVED) {
            q.setValidationNotes(truncate("Approved by " + actor.username() + (note == null ? "" : ": " + note), 1000));
        }
        q.setUpdatedAt(clock.instant());
        audit.admin(actor, "QUESTION_REVIEW_STATUS", "Question", q.getCode(), before + " -> " + target
                + (note == null ? "" : " (" + note + ")"), ip);
        return toDto(q);
    }

    @Transactional
    public QuestionAdminDto setActive(Long id, boolean active, AdminPrincipal actor, String ip) {
        Question q = find(id);
        q.setActive(active);
        q.setUpdatedAt(clock.instant());
        audit.admin(actor, active ? "QUESTION_ACTIVATED" : "QUESTION_DEACTIVATED", "Question", q.getCode(), null, ip);
        return toDto(q);
    }

    private Question find(Long id) {
        return questions.findById(id).orElseThrow(() -> ApiException.notFound("Question"));
    }

    private QuestionAdminDto toDto(Question q) {
        return new QuestionAdminDto(q.getId(), q.getCode(), q.getSection().name(), q.getSection().getLabel(),
                q.getDifficulty().name(), q.getText(), q.getOptionA(), q.getOptionB(), q.getOptionC(), q.getOptionD(),
                q.getCorrectOption(), q.getExplanation(), q.getReviewStatus().name(), q.getValidationNotes(),
                q.isActive(), attemptQuestions.existsByQuestionId(q.getId()), q.getSourceRow());
    }

    private static String notes(List<String> issues, List<String> warnings) {
        List<String> all = merge(issues, warnings);
        return all.isEmpty() ? null : truncate(String.join(" ", all), 1000);
    }

    private static List<String> merge(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_FILTER", "Unknown value '" + value + "'.");
        }
    }
}

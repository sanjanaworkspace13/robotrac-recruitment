package com.robotrac.recruitment.core;

import com.robotrac.recruitment.domain.Difficulty;
import com.robotrac.recruitment.domain.QuestionReviewStatus;
import com.robotrac.recruitment.domain.Section;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates question-bank rows. Nothing is silently dropped and no content is invented:
 * <ul>
 *   <li><b>REJECTED</b> - structurally unusable (missing ID, text or an option; unknown section or
 *       difficulty; duplicate ID; over-long text). Reported back to HR, not stored.</li>
 *   <li><b>FLAGGED</b> - stored exactly as written but excluded from papers until HR fixes/approves it
 *       (missing or invalid answer key, key text disagreeing with the option, identical options,
 *       duplicate question text).</li>
 *   <li><b>ACCEPTED</b> - stored and eligible. The workbook's "HR Review Status" is preserved.</li>
 * </ul>
 */
public final class QuestionRowValidator {

    public static final int MAX_CODE = 40;
    public static final int MAX_TEXT = 2000;
    public static final int MAX_OPTION = 500;
    public static final int MAX_EXPLANATION = 2000;

    public enum Outcome { ACCEPTED, FLAGGED, REJECTED }

    /** One spreadsheet row as plain strings (null when the cell is empty). */
    public record RawRow(int rowNumber, String code, String section, String difficulty, String text,
                         String optionA, String optionB, String optionC, String optionD,
                         String correctOption, String correctAnswer, String explanation,
                         String marks, String reviewStatus) {
    }

    public record ValidatedRow(RawRow raw, Outcome outcome, Section section, Difficulty difficulty,
                               String correctOption, BigDecimal marks, QuestionReviewStatus reviewStatus,
                               List<String> issues, List<String> warnings) {
    }

    private QuestionRowValidator() {
    }

    /**
     * @param rows               rows in file order
     * @param existingTextKeys   {@link #textKey} of questions already in the database (for duplicate detection)
     */
    public static List<ValidatedRow> validate(List<RawRow> rows, Set<String> existingTextKeys) {
        List<ValidatedRow> out = new ArrayList<>();
        Map<String, Integer> seenCodes = new HashMap<>();
        Map<String, Integer> seenTexts = new HashMap<>();
        for (RawRow r : rows) {
            out.add(validateRow(r, seenCodes, seenTexts, existingTextKeys));
        }
        return out;
    }

    private static ValidatedRow validateRow(RawRow r, Map<String, Integer> seenCodes,
                                            Map<String, Integer> seenTexts, Set<String> existingTextKeys) {
        List<String> rejects = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String code = clean(r.code());
        if (code == null) {
            rejects.add("Missing Question ID.");
        } else if (code.length() > MAX_CODE) {
            rejects.add("Question ID is longer than " + MAX_CODE + " characters.");
        } else {
            String codeKey = code.toUpperCase(Locale.ROOT);
            Integer firstRow = seenCodes.putIfAbsent(codeKey, r.rowNumber());
            if (firstRow != null) {
                rejects.add("Duplicate Question ID (first used on row " + firstRow + ").");
            }
        }

        String text = clean(r.text());
        if (text == null) {
            rejects.add("Missing question text.");
        } else if (text.length() > MAX_TEXT) {
            rejects.add("Question text is longer than " + MAX_TEXT + " characters.");
        }

        String[] options = {clean(r.optionA()), clean(r.optionB()), clean(r.optionC()), clean(r.optionD())};
        for (int i = 0; i < 4; i++) {
            char letter = (char) ('A' + i);
            if (options[i] == null) {
                rejects.add("Missing Option " + letter + ".");
            } else if (options[i].length() > MAX_OPTION) {
                rejects.add("Option " + letter + " is longer than " + MAX_OPTION + " characters.");
            }
        }

        Section section = parseSection(r.section());
        if (section == null) {
            rejects.add("Unknown section '" + nullToEmpty(r.section()) + "'.");
        }
        Difficulty difficulty = parseDifficulty(r.difficulty());
        if (difficulty == null) {
            rejects.add("Unknown difficulty '" + nullToEmpty(r.difficulty()) + "'.");
        }

        String explanation = clean(r.explanation());
        if (explanation != null && explanation.length() > MAX_EXPLANATION) {
            rejects.add("Explanation is longer than " + MAX_EXPLANATION + " characters.");
        }

        if (!rejects.isEmpty()) {
            return new ValidatedRow(r, Outcome.REJECTED, section, difficulty, null, null, null, rejects, warnings);
        }

        // Answer key
        String key = clean(r.correctOption());
        String correctOption = null;
        if (key == null) {
            issues.add("Missing answer key (Correct Option is empty) - needs HR review.");
        } else {
            String k = key.toUpperCase(Locale.ROOT);
            if (k.length() == 1 && k.charAt(0) >= 'A' && k.charAt(0) <= 'D') {
                correctOption = k;
            } else {
                issues.add("Invalid answer key '" + key + "' (must be A, B, C or D) - needs HR review.");
            }
        }
        String answerText = clean(r.correctAnswer());
        if (correctOption != null && answerText != null) {
            String keyed = options[correctOption.charAt(0) - 'A'];
            if (!comparable(keyed).equals(comparable(answerText))) {
                // Deliberately does not echo the answer text: these notes reach logs and the audit trail.
                issues.add("The 'Correct Answer' column does not match the text of the keyed option"
                        + " - ambiguous answer key, needs HR review.");
            }
        }

        // Identical options make a question ambiguous for candidates
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                if (comparable(options[i]).equals(comparable(options[j]))) {
                    issues.add("Option " + (char) ('A' + i) + " and Option " + (char) ('A' + j)
                            + " are identical ('" + options[i] + "') - needs HR review.");
                }
            }
        }

        // Duplicate question text
        String tk = textKey(text);
        Integer firstTextRow = seenTexts.putIfAbsent(tk, r.rowNumber());
        if (firstTextRow != null) {
            issues.add("Same question text as row " + firstTextRow + " - possible duplicate.");
        } else if (existingTextKeys != null && existingTextKeys.contains(tk)) {
            issues.add("Same question text as a question already in the bank - possible duplicate.");
        }

        BigDecimal marks = null;
        String marksText = clean(r.marks());
        if (marksText != null) {
            try {
                marks = new BigDecimal(marksText);
                if (marks.signum() <= 0) {
                    warnings.add("Marks value '" + marksText + "' ignored; drive marking scheme is used.");
                    marks = null;
                }
            } catch (NumberFormatException e) {
                warnings.add("Marks value '" + marksText + "' is not a number; drive marking scheme is used.");
            }
        }

        QuestionReviewStatus review = parseReviewStatus(r.reviewStatus());
        Outcome outcome = Outcome.ACCEPTED;
        if (!issues.isEmpty()) {
            review = QuestionReviewStatus.FLAGGED;
            outcome = Outcome.FLAGGED;
        }
        return new ValidatedRow(r, outcome, section, difficulty, correctOption, marks, review, issues, warnings);
    }

    public static Section parseSection(String value) {
        String k = letters(value);
        return switch (k) {
            case "quantitativeaptitude", "quantitative", "quant", "quantitativeability" -> Section.QUANTITATIVE;
            case "logicalreasoning", "logical", "reasoning" -> Section.LOGICAL;
            case "analyticalthinkingandproblemsolving", "analyticalthinkingproblemsolving",
                 "analyticalthinking", "analytical", "problemsolving", "analyticalreasoning" -> Section.ANALYTICAL;
            default -> null;
        };
    }

    public static Difficulty parseDifficulty(String value) {
        String k = letters(value);
        return switch (k) {
            case "easy" -> Difficulty.EASY;
            case "moderate", "medium" -> Difficulty.MODERATE;
            case "hard", "difficult" -> Difficulty.HARD;
            default -> null;
        };
    }

    private static QuestionReviewStatus parseReviewStatus(String value) {
        String k = letters(value);
        return k.startsWith("approved") ? QuestionReviewStatus.APPROVED : QuestionReviewStatus.NEEDS_REVIEW;
    }

    /** Key used to detect duplicate question wording. */
    public static String textKey(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Trims; returns null for null/blank. Wording is otherwise preserved exactly. */
    public static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    private static String comparable(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String letters(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT).replace("&", "and").replaceAll("[^a-z]", "");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

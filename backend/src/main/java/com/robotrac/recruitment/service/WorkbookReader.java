package com.robotrac.recruitment.service;

import com.robotrac.recruitment.core.QuestionRowValidator.RawRow;
import com.robotrac.recruitment.exception.ApiException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the HR question-bank workbook. Columns are located by their HEADER NAMES (not positions), matching
 * the supplied file "Robotrac_Company_Level_Aptitude_Bank_150_40min.xlsx", sheet "Recruitment Question Bank":
 * Question ID | Section | Difficulty | Question | Option A..D | Correct Option | Correct Answer | Explanation |
 * Marks | HR Review Status.
 */
@Component
public class WorkbookReader {

    public static final String PREFERRED_SHEET = "Recruitment Question Bank";
    private static final int MAX_ROWS = 5000;

    /** Normalised header -> logical column. */
    private static final Map<String, String> HEADER_ALIASES = new HashMap<>();

    static {
        alias("code", "questionid", "qid", "id", "questioncode");
        alias("section", "section", "category");
        alias("difficulty", "difficulty", "level");
        alias("text", "question", "questiontext");
        alias("a", "optiona");
        alias("b", "optionb");
        alias("c", "optionc");
        alias("d", "optiond");
        alias("key", "correctoption", "answerkey", "correctkey");
        alias("answer", "correctanswer");
        alias("explanation", "explanation");
        alias("marks", "marks", "mark");
        alias("review", "hrreviewstatus", "reviewstatus");
    }

    private static final List<String> REQUIRED = List.of("code", "section", "difficulty", "text", "a", "b", "c", "d", "key");

    public record ParsedWorkbook(String sheetName, int headerRow, List<RawRow> rows) {
    }

    public ParsedWorkbook read(InputStream input) {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet(PREFERRED_SHEET);
            if (sheet == null) {
                sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            }
            if (sheet == null) {
                throw invalid("The workbook has no sheets.");
            }
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            int headerIndex = sheet.getFirstRowNum();
            Row header = sheet.getRow(headerIndex);
            if (header == null) {
                throw invalid("The sheet '" + sheet.getSheetName() + "' has no header row.");
            }
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (Cell cell : header) {
                String key = HEADER_ALIASES.get(normalise(formatter.formatCellValue(cell, evaluator)));
                if (key != null && !columns.containsKey(key)) {
                    columns.put(key, cell.getColumnIndex());
                }
            }
            List<String> missing = REQUIRED.stream().filter(k -> !columns.containsKey(k)).toList();
            if (!missing.isEmpty()) {
                throw invalid("The header row is missing required column(s): " + String.join(", ", missing.stream()
                        .map(WorkbookReader::displayName).toList())
                        + ". Expected: Question ID, Section, Difficulty, Question, Option A-D, Correct Option.");
            }

            List<RawRow> rows = new ArrayList<>();
            for (int r = headerIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, String> v = new HashMap<>();
                boolean any = false;
                for (Map.Entry<String, Integer> col : columns.entrySet()) {
                    Cell cell = row.getCell(col.getValue());
                    String value = cell == null ? null : formatter.formatCellValue(cell, evaluator);
                    if (value != null && !value.isBlank()) {
                        any = true;
                    }
                    v.put(col.getKey(), value);
                }
                if (!any) {
                    continue; // completely empty row - not a question
                }
                if (rows.size() >= MAX_ROWS) {
                    throw invalid("The workbook has more than " + MAX_ROWS + " question rows.");
                }
                rows.add(new RawRow(r + 1, v.get("code"), v.get("section"), v.get("difficulty"), v.get("text"),
                        v.get("a"), v.get("b"), v.get("c"), v.get("d"), v.get("key"), v.get("answer"),
                        v.get("explanation"), v.get("marks"), v.get("review")));
            }
            return new ParsedWorkbook(sheet.getSheetName(), headerIndex + 1, rows);
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalid("The file could not be read as an Excel workbook (.xlsx). " +
                    "Save it from Excel as .xlsx and try again.");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WORKBOOK", message);
    }

    private static void alias(String key, String... names) {
        for (String n : names) {
            HEADER_ALIASES.put(n, key);
        }
    }

    private static String normalise(String header) {
        return header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String displayName(String key) {
        return switch (key) {
            case "code" -> "Question ID";
            case "text" -> "Question";
            case "a", "b", "c", "d" -> "Option " + key.toUpperCase(Locale.ROOT);
            case "key" -> "Correct Option";
            default -> Character.toUpperCase(key.charAt(0)) + key.substring(1);
        };
    }
}

package com.robotrac.recruitment.service;

import com.robotrac.recruitment.core.QuestionRowValidator;
import com.robotrac.recruitment.core.QuestionRowValidator.Outcome;
import com.robotrac.recruitment.core.QuestionRowValidator.ValidatedRow;
import com.robotrac.recruitment.exception.ApiException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reads the REAL supplied workbook plus synthetic broken workbooks. */
class WorkbookReaderTest {

    private final WorkbookReader reader = new WorkbookReader();

    @Test
    void readsTheSuppliedQuestionBank() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/question-bank.xlsx")) {
            WorkbookReader.ParsedWorkbook wb = reader.read(in);
            assertThat(wb.sheetName()).isEqualTo("Recruitment Question Bank");
            assertThat(wb.rows()).hasSize(150);
            assertThat(wb.rows().get(0).code()).isEqualTo("RBT-001");
            assertThat(wb.rows().get(0).optionB()).isEqualTo("72");

            List<ValidatedRow> validated = QuestionRowValidator.validate(wb.rows(), Set.of());
            assertThat(validated).filteredOn(v -> v.outcome() == Outcome.REJECTED).isEmpty();
            // Only RBT-034 (identical options A and C) needs HR attention.
            assertThat(validated).filteredOn(v -> v.outcome() == Outcome.FLAGGED)
                    .extracting(v -> v.raw().code()).containsExactly("RBT-034");
            assertThat(validated).filteredOn(v -> v.outcome() == Outcome.ACCEPTED).hasSize(149);
        }
    }

    @Test
    void malformedRowsAreReportedNotDropped() throws Exception {
        byte[] bytes = workbook(new String[][]{
                {"Question ID", "Section", "Difficulty", "Question", "Option A", "Option B", "Option C", "Option D", "Correct Option", "Correct Answer"},
                {"T-1", "Logical Reasoning", "Easy", "Valid?", "a", "b", "c", "d", "A", "a"},
                {"T-2", "Logical Reasoning", "Easy", "", "a", "b", "c", "d", "A", ""},            // missing text
                {"T-3", "Logical Reasoning", "Easy", "No key", "a", "b", "c", "d", "", ""},       // missing key
                {"T-4", "Logical Reasoning", "Easy", "Bad key", "a", "b", "c", "d", "Z", ""},     // invalid key
                {"T-5", "Unknown", "Easy", "Bad section", "a", "b", "c", "d", "A", ""},          // bad section
        });
        WorkbookReader.ParsedWorkbook wb = reader.read(new ByteArrayInputStream(bytes));
        List<ValidatedRow> v = QuestionRowValidator.validate(wb.rows(), Set.of());
        assertThat(v).hasSize(5);
        assertThat(v).extracting(ValidatedRow::outcome).containsExactly(
                Outcome.ACCEPTED, Outcome.REJECTED, Outcome.FLAGGED, Outcome.FLAGGED, Outcome.REJECTED);
        assertThat(v.get(1).raw().rowNumber()).isEqualTo(3); // Excel row numbers are reported
    }

    @Test
    void missingRequiredColumnIsAClearError() throws Exception {
        byte[] bytes = workbook(new String[][]{{"Question ID", "Section", "Question"}, {"T-1", "Logical Reasoning", "Q"}});
        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(bytes)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Correct Option");
    }

    @Test
    void nonExcelFileIsRejected() {
        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream("not a workbook".getBytes())))
                .isInstanceOf(ApiException.class);
    }

    private static byte[] workbook(String[][] cells) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(WorkbookReader.PREFERRED_SHEET);
            for (int r = 0; r < cells.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < cells[r].length; c++) {
                    row.createCell(c).setCellValue(cells[r][c]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}

package com.robotrac.recruitment.service;

import com.robotrac.recruitment.domain.ActorType;
import com.robotrac.recruitment.repository.QuestionRepository;
import com.robotrac.recruitment.web.dto.QuestionDtos.ImportReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * On first start (empty question table) imports the supplied workbook automatically, with the same
 * validation as the HR upload. Later imports are done from the Question Bank page.
 */
@Component
public class QuestionBankSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QuestionBankSeeder.class);

    private final QuestionBankService questionBankService;
    private final QuestionRepository questionRepository;
    private final boolean enabled;
    private final String seedFile;

    public QuestionBankSeeder(QuestionBankService questionBankService, QuestionRepository questionRepository,
                              @Value("${app.question-bank.seed-on-startup:false}") boolean enabled,
                              @Value("${app.question-bank.seed-file:}") String seedFile) {
        this.questionBankService = questionBankService;
        this.questionRepository = questionRepository;
        this.enabled = enabled;
        this.seedFile = seedFile;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || seedFile == null || seedFile.isBlank()) {
            return;
        }
        if (questionRepository.count() > 0) {
            log.info("Question bank already contains {} questions; startup import skipped.", questionRepository.count());
            return;
        }
        Path path = Path.of(seedFile).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            log.warn("Question bank seed file not found at {}. Import it from the HR Question Bank page instead.", path);
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            ImportReport report = questionBankService.importWorkbook(in, path.getFileName().toString(),
                    ActorType.SYSTEM, null, "startup-seed", null);
            log.info("Question bank imported from {}: {} imported, {} flagged for HR review, {} rejected, {} skipped.",
                    path.getFileName(), report.imported(), report.flagged(), report.rejected(), report.skippedExisting());
            report.rows().stream().filter(r -> !r.outcome().equals("IMPORTED"))
                    .forEach(r -> log.warn("  row {} ({}): {} - {}", r.row(), r.code(), r.outcome(), String.join(" ", r.messages())));
        } catch (Exception e) {
            log.error("Startup import of the question bank failed: {}", e.getMessage());
        }
    }
}

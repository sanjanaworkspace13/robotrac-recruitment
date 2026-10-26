package com.robotrac.recruitment.web.controller;

import com.robotrac.recruitment.domain.ActorType;
import com.robotrac.recruitment.exception.ApiException;
import com.robotrac.recruitment.security.AdminPrincipal;
import com.robotrac.recruitment.security.ClientInfo;
import com.robotrac.recruitment.security.CurrentUser;
import com.robotrac.recruitment.service.QuestionBankService;
import com.robotrac.recruitment.web.dto.PageResponse;
import com.robotrac.recruitment.web.dto.QuestionDtos.ImportReport;
import com.robotrac.recruitment.web.dto.QuestionDtos.QuestionActiveRequest;
import com.robotrac.recruitment.web.dto.QuestionDtos.QuestionAdminDto;
import com.robotrac.recruitment.web.dto.QuestionDtos.QuestionBankSummary;
import com.robotrac.recruitment.web.dto.QuestionDtos.QuestionReviewRequest;
import com.robotrac.recruitment.web.dto.QuestionDtos.QuestionUpdateRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** HR: question bank (ROLE_ADMIN only). These responses include answer keys. */
@RestController
@RequestMapping("/api/admin/questions")
public class AdminQuestionController {

    private final QuestionBankService questions;

    public AdminQuestionController(QuestionBankService questions) {
        this.questions = questions;
    }

    @GetMapping
    public PageResponse<QuestionAdminDto> search(@RequestParam(required = false) String section,
                                                 @RequestParam(required = false) String difficulty,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "25") int size) {
        return questions.search(section, difficulty, status, q, page, size);
    }

    @GetMapping("/summary")
    public QuestionBankSummary summary() {
        return questions.summary();
    }

    @GetMapping("/{id}")
    public QuestionAdminDto get(@PathVariable Long id) {
        return questions.get(id);
    }

    @PutMapping("/{id}")
    public QuestionAdminDto update(@PathVariable Long id, @Valid @RequestBody QuestionUpdateRequest req,
                                   HttpServletRequest request) {
        return questions.update(id, req, CurrentUser.admin(), ClientInfo.ip(request));
    }

    @PutMapping("/{id}/review")
    public QuestionAdminDto review(@PathVariable Long id, @Valid @RequestBody QuestionReviewRequest req,
                                   HttpServletRequest request) {
        return questions.setReviewStatus(id, req, CurrentUser.admin(), ClientInfo.ip(request));
    }

    @PutMapping("/{id}/active")
    public QuestionAdminDto active(@PathVariable Long id, @Valid @RequestBody QuestionActiveRequest req,
                                   HttpServletRequest request) {
        return questions.setActive(id, req.active(), CurrentUser.admin(), ClientInfo.ip(request));
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public ImportReport importWorkbook(@RequestPart("file") MultipartFile file, HttpServletRequest request) throws IOException {
        String name = file.getOriginalFilename() == null ? "workbook.xlsx" : file.getOriginalFilename();
        if (file.isEmpty() || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw ApiException.badRequest("INVALID_FILE", "Upload the question bank as an Excel .xlsx file.");
        }
        AdminPrincipal admin = CurrentUser.admin();
        String safeName = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        try (InputStream in = file.getInputStream()) {
            return questions.importWorkbook(in, safeName, ActorType.ADMIN, admin.adminId(), admin.username(),
                    ClientInfo.ip(request));
        }
    }
}

package com.robotrac.recruitment.web.controller;

import com.robotrac.recruitment.domain.AttemptStatus;
import com.robotrac.recruitment.security.ClientInfo;
import com.robotrac.recruitment.security.CurrentUser;
import com.robotrac.recruitment.service.ResultService;
import com.robotrac.recruitment.web.dto.PageResponse;
import com.robotrac.recruitment.web.dto.ResultDtos.AttemptReviewRequest;
import com.robotrac.recruitment.web.dto.ResultDtos.EventDto;
import com.robotrac.recruitment.web.dto.ResultDtos.ResultDetailDto;
import com.robotrac.recruitment.web.dto.ResultDtos.ResultRowDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/** HR: results and exam-integrity history (ROLE_ADMIN only). */
@RestController
@RequestMapping("/api/admin/results")
public class AdminResultController {

    private final ResultService results;

    public AdminResultController(ResultService results) {
        this.results = results;
    }

    @GetMapping
    public PageResponse<ResultRowDto> search(@RequestParam(required = false) Long driveId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "startedAt") String sort,
                                             @RequestParam(defaultValue = "desc") String dir,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "25") int size) {
        return results.search(filter(driveId, status, q), sort, dir, page, size);
    }

    @GetMapping("/{attemptId}")
    public ResultDetailDto detail(@PathVariable Long attemptId) {
        return results.detail(attemptId);
    }

    @GetMapping("/{attemptId}/events")
    public List<EventDto> events(@PathVariable Long attemptId) {
        return results.events(attemptId);
    }

    @PutMapping("/{attemptId}/review")
    public ResultDetailDto review(@PathVariable Long attemptId, @Valid @RequestBody AttemptReviewRequest req,
                                  HttpServletRequest request) {
        return results.review(attemptId, req, CurrentUser.admin(), ClientInfo.ip(request));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Long driveId,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String q,
                                         HttpServletRequest request) {
        String csv = results.exportCsv(filter(driveId, status, q), CurrentUser.admin(), ClientInfo.ip(request));
        String fileName = "robotrac-results-" + LocalDate.now(ZoneOffset.UTC) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private static ResultService.Filter filter(Long driveId, String status, String q) {
        AttemptStatus st = null;
        if (status != null && !status.isBlank()) {
            try {
                st = AttemptStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw com.robotrac.recruitment.exception.ApiException.badRequest("INVALID_FILTER", "Unknown status filter.");
            }
        }
        return new ResultService.Filter(driveId, st, q);
    }
}

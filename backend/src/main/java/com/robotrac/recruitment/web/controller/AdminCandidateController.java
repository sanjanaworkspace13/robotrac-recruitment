package com.robotrac.recruitment.web.controller;

import com.robotrac.recruitment.exception.ApiException;
import com.robotrac.recruitment.security.ClientInfo;
import com.robotrac.recruitment.security.CurrentUser;
import com.robotrac.recruitment.service.CandidateAuthorizationService;
import com.robotrac.recruitment.web.dto.DriveDtos.AddCandidateRequest;
import com.robotrac.recruitment.web.dto.DriveDtos.AuthorizedCandidateDto;
import com.robotrac.recruitment.web.dto.DriveDtos.BulkCandidatesRequest;
import com.robotrac.recruitment.web.dto.DriveDtos.CandidateImportReport;
import com.robotrac.recruitment.web.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** HR: candidates authorised for a drive (ROLE_ADMIN only). */
@RestController
@RequestMapping("/api/admin/drives/{driveId}/candidates")
public class AdminCandidateController {

    private static final long MAX_CSV_BYTES = 1024 * 1024;

    private final CandidateAuthorizationService service;

    public AdminCandidateController(CandidateAuthorizationService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<AuthorizedCandidateDto> list(@PathVariable Long driveId,
                                                     @RequestParam(required = false) String q,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "25") int size) {
        return service.list(driveId, q, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AuthorizedCandidateDto add(@PathVariable Long driveId, @Valid @RequestBody AddCandidateRequest req,
                                      HttpServletRequest request) {
        return service.addOne(driveId, req.email(), req.fullName(), CurrentUser.admin(), ClientInfo.ip(request));
    }

    @PostMapping("/bulk")
    public CandidateImportReport bulk(@PathVariable Long driveId, @Valid @RequestBody BulkCandidatesRequest req,
                                      HttpServletRequest request) {
        return service.addBulk(driveId, req.text(), CurrentUser.admin(), ClientInfo.ip(request));
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public CandidateImportReport importCsv(@PathVariable Long driveId, @RequestPart("file") MultipartFile file,
                                           HttpServletRequest request) throws IOException {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (file.isEmpty() || !(name.endsWith(".csv") || name.endsWith(".txt"))) {
            throw ApiException.badRequest("INVALID_FILE", "Upload a .csv file with one email per line (optionally email,full name).");
        }
        if (file.getSize() > MAX_CSV_BYTES) {
            throw ApiException.badRequest("FILE_TOO_LARGE", "The CSV file must be smaller than 1 MB.");
        }
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return service.addBulk(driveId, content, CurrentUser.admin(), ClientInfo.ip(request));
    }

    @DeleteMapping("/{authorizationId}")
    public ResponseEntity<Void> revoke(@PathVariable Long driveId, @PathVariable Long authorizationId,
                                       HttpServletRequest request) {
        service.revoke(driveId, authorizationId, CurrentUser.admin(), ClientInfo.ip(request));
        return ResponseEntity.noContent().build();
    }
}

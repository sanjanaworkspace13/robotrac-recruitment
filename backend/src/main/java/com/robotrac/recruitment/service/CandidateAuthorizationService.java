package com.robotrac.recruitment.service;

import com.robotrac.recruitment.core.CsvUtil;
import com.robotrac.recruitment.core.EmailNormalizer;
import com.robotrac.recruitment.domain.AdminUser;
import com.robotrac.recruitment.domain.AuthorizationStatus;
import com.robotrac.recruitment.domain.Candidate;
import com.robotrac.recruitment.domain.DriveAuthorization;
import com.robotrac.recruitment.domain.DriveStatus;
import com.robotrac.recruitment.domain.ExamAttempt;
import com.robotrac.recruitment.domain.RecruitmentDrive;
import com.robotrac.recruitment.exception.ApiException;
import com.robotrac.recruitment.repository.AdminUserRepository;
import com.robotrac.recruitment.repository.CandidateRepository;
import com.robotrac.recruitment.repository.DriveAuthorizationRepository;
import com.robotrac.recruitment.repository.ExamAttemptRepository;
import com.robotrac.recruitment.security.AdminPrincipal;
import com.robotrac.recruitment.web.dto.DriveDtos.AuthorizedCandidateDto;
import com.robotrac.recruitment.web.dto.DriveDtos.CandidateImportReport;
import com.robotrac.recruitment.web.dto.DriveDtos.LineError;
import com.robotrac.recruitment.web.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Which candidate emails may sit which drive.
 * <ul>
 *   <li>Emails are normalised (trimmed, lower-case) and validated; malformed and duplicate entries are reported.</li>
 *   <li>Bulk add accepts pasted text or a CSV file: one candidate per line, "email" or "email,full name";
 *       a header line starting with "email" is ignored.</li>
 *   <li><b>Removal policy:</b> authorisation can be revoked only while the candidate has NOT started an
 *       attempt. Once an attempt exists the authorisation is kept forever with its result. Revocation is a
 *       status change (never a delete) and is audited.</li>
 * </ul>
 */
@Service
public class CandidateAuthorizationService {

    public static final int MAX_BULK_LINES = 2000;

    private final DriveService driveService;
    private final CandidateRepository candidates;
    private final DriveAuthorizationRepository authorizations;
    private final ExamAttemptRepository attempts;
    private final AdminUserRepository admins;
    private final AuditService audit;
    private final Clock clock;

    public CandidateAuthorizationService(DriveService driveService, CandidateRepository candidates,
                                         DriveAuthorizationRepository authorizations, ExamAttemptRepository attempts,
                                         AdminUserRepository admins, AuditService audit, Clock clock) {
        this.driveService = driveService;
        this.candidates = candidates;
        this.authorizations = authorizations;
        this.attempts = attempts;
        this.admins = admins;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuthorizedCandidateDto> list(Long driveId, String q, int page, int size) {
        driveService.find(driveId);
        String like = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        Page<DriveAuthorization> result = authorizations.search(driveId, like,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by("createdAt").descending()));
        Set<Long> candidateIds = result.getContent().stream().map(a -> a.getCandidate().getId()).collect(Collectors.toSet());
        Map<Long, ExamAttempt> attemptByCandidate = candidateIds.isEmpty() ? Map.of()
                : attempts.findByDriveIdAndCandidateIdIn(driveId, candidateIds).stream()
                .collect(Collectors.toMap(a -> a.getCandidate().getId(), Function.identity()));
        return PageResponse.of(result, a -> {
            ExamAttempt at = attemptByCandidate.get(a.getCandidate().getId());
            return new AuthorizedCandidateDto(a.getId(), a.getCandidate().getId(), a.getCandidate().getEmail(),
                    a.getCandidate().getFullName(), a.getStatus().name(), a.getCreatedAt(), a.getRevokedAt(),
                    at == null ? null : at.getId(), at == null ? "NOT_STARTED" : at.getStatus().name());
        });
    }

    @Transactional
    public AuthorizedCandidateDto addOne(Long driveId, String email, String fullName, AdminPrincipal actor, String ip) {
        RecruitmentDrive drive = requireOpenDrive(driveId);
        String normalized = EmailNormalizer.normalize(email);
        if (!EmailNormalizer.isValid(normalized)) {
            throw ApiException.badRequest("INVALID_EMAIL", "Enter a valid email address.");
        }
        AdminUser admin = admins.getReferenceById(actor.adminId());
        Outcome outcome = authorize(drive, normalized, cleanName(fullName), admin);
        if (outcome == Outcome.ALREADY) {
            throw ApiException.conflict("ALREADY_AUTHORIZED", normalized + " is already authorised for this drive.");
        }
        audit.admin(actor, outcome == Outcome.ADDED ? "CANDIDATE_AUTHORIZED" : "CANDIDATE_REAUTHORIZED",
                "RecruitmentDrive", driveId, normalized, ip);
        DriveAuthorization a = authorizations.findByDriveIdAndEmail(driveId, normalized).orElseThrow();
        return new AuthorizedCandidateDto(a.getId(), a.getCandidate().getId(), a.getCandidate().getEmail(),
                a.getCandidate().getFullName(), a.getStatus().name(), a.getCreatedAt(), a.getRevokedAt(), null, "NOT_STARTED");
    }

    /** Adds many candidates from pasted text or CSV content. Valid lines are added even if others fail. */
    @Transactional
    public CandidateImportReport addBulk(Long driveId, String content, AdminPrincipal actor, String ip) {
        RecruitmentDrive drive = requireOpenDrive(driveId);
        AdminUser admin = admins.getReferenceById(actor.adminId());
        List<List<String>> rows = CsvUtil.parse(content);
        if (rows.size() > MAX_BULK_LINES) {
            throw ApiException.badRequest("TOO_MANY_LINES", "Please add at most " + MAX_BULK_LINES + " candidates at a time.");
        }
        int added = 0;
        int reactivated = 0;
        int already = 0;
        List<LineError> invalid = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            int line = i + 1;
            if (cells.isEmpty() || cells.stream().allMatch(c -> c == null || c.isBlank())) {
                continue;
            }
            String rawEmail = cells.get(0);
            if (i == 0 && rawEmail != null && rawEmail.trim().toLowerCase(Locale.ROOT).startsWith("email")) {
                continue; // header row
            }
            String email = EmailNormalizer.normalize(rawEmail);
            if (!EmailNormalizer.isValid(email)) {
                invalid.add(new LineError(line, safe(rawEmail), "Not a valid email address."));
                continue;
            }
            if (!seen.add(email)) {
                invalid.add(new LineError(line, email, "Duplicate of an earlier line."));
                continue;
            }
            String name = cells.size() > 1 ? cleanName(cells.get(1)) : null;
            switch (authorize(drive, email, name, admin)) {
                case ADDED -> added++;
                case REACTIVATED -> reactivated++;
                case ALREADY -> already++;
            }
        }
        audit.admin(actor, "CANDIDATES_BULK_AUTHORIZED", "RecruitmentDrive", driveId,
                String.format("added=%d reactivated=%d already=%d invalid=%d", added, reactivated, already, invalid.size()), ip);
        return new CandidateImportReport(added, reactivated, already, invalid);
    }

    @Transactional
    public void revoke(Long driveId, Long authorizationId, AdminPrincipal actor, String ip) {
        DriveAuthorization a = authorizations.findById(authorizationId)
                .filter(x -> x.getDrive().getId().equals(driveId))
                .orElseThrow(() -> ApiException.notFound("Authorisation"));
        if (a.getStatus() == AuthorizationStatus.REVOKED) {
            throw ApiException.conflict("ALREADY_REVOKED", "This authorisation is already revoked.");
        }
        if (attempts.existsByDriveIdAndCandidateId(driveId, a.getCandidate().getId())) {
            throw ApiException.conflict("ATTEMPT_EXISTS",
                    "This candidate has already started the test, so the authorisation is kept with the result and cannot be removed.");
        }
        a.setStatus(AuthorizationStatus.REVOKED);
        a.setRevokedAt(clock.instant());
        audit.admin(actor, "CANDIDATE_REVOKED", "RecruitmentDrive", driveId, a.getCandidate().getEmail(), ip);
    }

    private enum Outcome { ADDED, REACTIVATED, ALREADY }

    private Outcome authorize(RecruitmentDrive drive, String email, String fullName, AdminUser admin) {
        Instant now = clock.instant();
        Candidate candidate = candidates.findByEmail(email).orElseGet(() -> candidates.save(new Candidate(email, fullName, now)));
        if (fullName != null && candidate.getFullName() == null) {
            candidate.setFullName(fullName);
        }
        DriveAuthorization existing = authorizations.findByDriveIdAndCandidateId(drive.getId(), candidate.getId()).orElse(null);
        if (existing == null) {
            authorizations.save(new DriveAuthorization(drive, candidate, admin, now));
            return Outcome.ADDED;
        }
        if (existing.getStatus() == AuthorizationStatus.REVOKED) {
            existing.setStatus(AuthorizationStatus.AUTHORIZED);
            existing.setRevokedAt(null);
            existing.setAddedBy(admin);
            return Outcome.REACTIVATED;
        }
        return Outcome.ALREADY;
    }

    private RecruitmentDrive requireOpenDrive(Long driveId) {
        RecruitmentDrive drive = driveService.find(driveId);
        if (drive.getStatus() == DriveStatus.CLOSED) {
            throw ApiException.conflict("DRIVE_CLOSED", "Candidates cannot be added to a closed drive.");
        }
        return drive;
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.strip();
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 100 ? s.substring(0, 100) : s;
    }
}

package com.robotrac.recruitment.service;

import com.robotrac.recruitment.core.EmailNormalizer;
import com.robotrac.recruitment.domain.ActorType;
import com.robotrac.recruitment.domain.AttemptStatus;
import com.robotrac.recruitment.domain.AuthorizationStatus;
import com.robotrac.recruitment.domain.DriveAuthorization;
import com.robotrac.recruitment.domain.ExamAttempt;
import com.robotrac.recruitment.domain.FinalizedBy;
import com.robotrac.recruitment.domain.MonitoringEventType;
import com.robotrac.recruitment.domain.RecruitmentDrive;
import com.robotrac.recruitment.exception.ApiException;
import com.robotrac.recruitment.exception.AttemptClosedException;
import com.robotrac.recruitment.repository.DriveAuthorizationRepository;
import com.robotrac.recruitment.repository.ExamAttemptRepository;
import com.robotrac.recruitment.repository.RecruitmentDriveRepository;
import com.robotrac.recruitment.security.CandidatePrincipal;
import com.robotrac.recruitment.web.dto.AuthDtos.CandidateLoginRequest;
import com.robotrac.recruitment.web.dto.AuthDtos.CandidateLoginResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/**
 * Candidate login = registered email + drive code + shared drive password, all verified on the server.
 *
 * <ol>
 *   <li>Rate limit (failed attempts per email+drive and per IP).</li>
 *   <li>Password is always checked with BCrypt, even when the drive code is unknown, so response time does
 *       not reveal which codes exist.</li>
 *   <li>The email must be on the drive's authorised list (knowing the code and password is not enough).</li>
 *   <li>Any of the above failing gives the same generic message.</li>
 *   <li>Only after the credentials are proven correct does the candidate get a specific reason
 *       (not open yet, ended, closed, already completed).</li>
 *   <li>A candidate whose attempt is IN PROGRESS may always log back in to resume it (same timer).</li>
 * </ol>
 */
@Service
public class CandidateAuthService {

    private static final String SCOPE = "candidate-login";
    private static final String GENERIC_FAILURE =
            "We could not verify these details. Check your registered email ID, drive code and drive password.";

    private final RecruitmentDriveRepository drives;
    private final DriveAuthorizationRepository authorizations;
    private final ExamAttemptRepository attempts;
    private final FinalizationService finalization;
    private final MonitoringService monitoring;
    private final ExamPolicy policy;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottle throttle;
    private final AuditService audit;
    private final Clock clock;
    private final String timingDummyHash;

    public record LoginResult(CandidatePrincipal principal, CandidateLoginResponse response) {
    }

    public CandidateAuthService(RecruitmentDriveRepository drives, DriveAuthorizationRepository authorizations,
                                ExamAttemptRepository attempts, FinalizationService finalization,
                                MonitoringService monitoring, ExamPolicy policy, PasswordEncoder passwordEncoder,
                                LoginThrottle throttle, AuditService audit, Clock clock) {
        this.drives = drives;
        this.authorizations = authorizations;
        this.attempts = attempts;
        this.finalization = finalization;
        this.monitoring = monitoring;
        this.policy = policy;
        this.passwordEncoder = passwordEncoder;
        this.throttle = throttle;
        this.audit = audit;
        this.clock = clock;
        this.timingDummyHash = passwordEncoder.encode("timing-equaliser-not-a-real-drive-password");
    }

    /** Name of the drive the logged-in candidate belongs to (for the page header). */
    @Transactional(readOnly = true)
    public String driveName(CandidatePrincipal p) {
        return drives.findById(p.driveId()).map(RecruitmentDrive::getName).orElse(null);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginResult login(CandidateLoginRequest req, String ip) {
        String email = EmailNormalizer.normalize(req.email());
        String code = req.driveCode().trim().toUpperCase(Locale.ROOT);
        String account = email + "|" + code;
        if (!EmailNormalizer.isValid(email)) {
            throw ApiException.badRequest("INVALID_EMAIL", "Please enter a valid email address.");
        }
        throttle.checkAllowed(SCOPE, account, ip);

        RecruitmentDrive drive = drives.findByCode(code).orElse(null);
        boolean hasPassword = drive != null && drive.hasPassword();
        boolean passwordOk = passwordEncoder.matches(req.drivePassword(), hasPassword ? drive.getPasswordHash() : timingDummyHash)
                && hasPassword;
        DriveAuthorization auth = drive == null ? null : authorizations.findByDriveIdAndEmail(drive.getId(), email).orElse(null);

        if (!passwordOk || auth == null || auth.getStatus() != AuthorizationStatus.AUTHORIZED) {
            throttle.recordFailure(SCOPE, account, ip);
            audit.record(ActorType.ANONYMOUS, null, email, "CANDIDATE_LOGIN_FAILED", "RecruitmentDrive", code,
                    !passwordOk ? "wrong drive code or password" : "email not authorised for drive", ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", GENERIC_FAILURE);
        }
        // Credentials are correct from here on.
        throttle.recordSuccess(SCOPE, account);
        Instant now = clock.instant();
        Long candidateId = auth.getCandidate().getId();
        ExamAttempt attempt = attempts.findForUpdate(drive.getId(), candidateId).orElse(null);
        if (attempt != null && attempt.getStatus() == AttemptStatus.IN_PROGRESS && policy.isExpired(attempt, now)) {
            finalization.finalizeLocked(attempt, AttemptStatus.TIMED_OUT, FinalizedBy.SERVER_DEADLINE, now);
        }
        if (attempt != null && attempt.getStatus().isFinal()) {
            audit.record(ActorType.CANDIDATE, candidateId, email, "CANDIDATE_LOGIN_REFUSED", "RecruitmentDrive",
                    drive.getId(), "attempt already completed", ip);
            throw new AttemptClosedException("You have already completed this assessment. Only one attempt is allowed per drive.");
        }
        if (attempt == null) {
            ExamService.requireOpenWindow(drive, now); // not open yet / ended / closed / draft
        }

        CandidatePrincipal principal = new CandidatePrincipal(candidateId, drive.getId(), email, drive.getCode());
        if (attempt != null) {
            monitoring.recordServerEvent(attempt, MonitoringEventType.LOGIN, "Logged in again during the attempt from " + ip);
        }
        audit.record(ActorType.CANDIDATE, candidateId, email, "CANDIDATE_LOGIN", "RecruitmentDrive", drive.getId(),
                attempt != null ? "resuming attempt " + attempt.getId() : null, ip);
        return new LoginResult(principal, new CandidateLoginResponse(email, drive.getCode(), drive.getName(),
                attempt != null ? "EXAM" : "INSTRUCTIONS"));
    }
}

package com.robotrac.recruitment.service;

import com.robotrac.recruitment.core.PaperAllocator;
import com.robotrac.recruitment.core.PasswordPolicy;
import com.robotrac.recruitment.domain.AdminUser;
import com.robotrac.recruitment.domain.AttemptStatus;
import com.robotrac.recruitment.domain.AuthorizationStatus;
import com.robotrac.recruitment.domain.Difficulty;
import com.robotrac.recruitment.domain.DrivePhase;
import com.robotrac.recruitment.domain.DriveStatus;
import com.robotrac.recruitment.domain.RecruitmentDrive;
import com.robotrac.recruitment.domain.Section;
import com.robotrac.recruitment.exception.ApiException;
import com.robotrac.recruitment.repository.AdminUserRepository;
import com.robotrac.recruitment.repository.DriveAuthorizationRepository;
import com.robotrac.recruitment.repository.ExamAttemptRepository;
import com.robotrac.recruitment.repository.RecruitmentDriveRepository;
import com.robotrac.recruitment.security.AdminPrincipal;
import com.robotrac.recruitment.web.dto.DriveDtos.ActivationResultDto;
import com.robotrac.recruitment.web.dto.DriveDtos.AvailabilityDto;
import com.robotrac.recruitment.web.dto.DriveDtos.CellDto;
import com.robotrac.recruitment.web.dto.DriveDtos.DriveDto;
import com.robotrac.recruitment.web.dto.DriveDtos.DriveRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Recruitment drive lifecycle: DRAFT -> ACTIVE -> CLOSED.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>DRAFT: everything editable. Activation requires a drive password, an end time in the future and a
 *       question bank that can satisfy the difficulty mix (checked with {@link PaperAllocator}).</li>
 *   <li>ACTIVE: only name, description, end time and warning threshold may change. "Deactivate" (back to
 *       DRAFT) is allowed only while no attempt has started.</li>
 *   <li>CLOSED: final. No new logins or attempts. <b>Policy for attempts already in progress when HR closes
 *       a drive:</b> they continue until their own deadline (which never exceeds the drive end time) and are
 *       then finalised normally, so no candidate loses answers because of an administrative action.</li>
 *   <li>Nothing is deleted: results, monitoring events and audit history are always kept.</li>
 * </ul>
 */
@Service
public class DriveService {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9-]{3,19}$");

    private final RecruitmentDriveRepository drives;
    private final DriveAuthorizationRepository authorizations;
    private final ExamAttemptRepository attempts;
    private final AdminUserRepository admins;
    private final QuestionBankService questionBank;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Clock clock;

    public DriveService(RecruitmentDriveRepository drives, DriveAuthorizationRepository authorizations,
                        ExamAttemptRepository attempts, AdminUserRepository admins, QuestionBankService questionBank,
                        PasswordEncoder passwordEncoder, AuditService audit, Clock clock) {
        this.drives = drives;
        this.authorizations = authorizations;
        this.attempts = attempts;
        this.admins = admins;
        this.questionBank = questionBank;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<DriveDto> list() {
        Stats stats = loadStats();
        return drives.findAllByOrderByCreatedAtDesc().stream().map(d -> toDto(d, stats)).toList();
    }

    @Transactional(readOnly = true)
    public DriveDto get(Long id) {
        return toDto(find(id), loadStats());
    }

    @Transactional
    public DriveDto create(DriveRequest req, AdminPrincipal actor, String ip) {
        String code = normaliseCode(req.code());
        if (drives.existsByCode(code)) {
            throw ApiException.conflict("DUPLICATE_DRIVE_CODE", "Drive code " + code + " is already used by another drive.");
        }
        Instant now = clock.instant();
        validateConfig(req, now, true);
        AdminUser creator = admins.findById(actor.adminId()).orElseThrow(() -> ApiException.notFound("Admin account"));

        RecruitmentDrive d = new RecruitmentDrive();
        d.setCode(code);
        applyAll(d, req);
        if (req.drivePassword() != null && !req.drivePassword().isBlank()) {
            d.setPasswordHash(hashDrivePassword(req.drivePassword()));
        }
        d.setStatus(DriveStatus.DRAFT);
        d.setCreatedBy(creator);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        drives.save(d);
        audit.admin(actor, "DRIVE_CREATED", "RecruitmentDrive", d.getId(), "code=" + code
                + (d.hasPassword() ? " (password set)" : ""), ip);
        return toDto(d, loadStats());
    }

    @Transactional
    public DriveDto update(Long id, DriveRequest req, AdminPrincipal actor, String ip) {
        RecruitmentDrive d = find(id);
        Instant now = clock.instant();
        switch (d.getStatus()) {
            case CLOSED -> throw ApiException.conflict("DRIVE_CLOSED", "A closed drive cannot be edited.");
            case DRAFT -> {
                String code = normaliseCode(req.code());
                if (!code.equals(d.getCode()) && drives.existsByCode(code)) {
                    throw ApiException.conflict("DUPLICATE_DRIVE_CODE", "Drive code " + code + " is already used by another drive.");
                }
                validateConfig(req, now, true);
                d.setCode(code);
                applyAll(d, req);
            }
            case ACTIVE -> {
                List<String> locked = lockedFieldChanges(d, req);
                if (!locked.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "DRIVE_ACTIVE_LOCKED",
                            "While a drive is active only its name, description, end time and warning threshold can "
                                    + "change. Changed locked field(s): " + String.join(", ", locked) + ".", locked);
                }
                if (req.endAt() == null || !req.endAt().isAfter(now)) {
                    throw ApiException.badRequest("INVALID_DATES", "The end time must be in the future.");
                }
                if (req.endAt().isBefore(d.getStartAt().plus(Duration.ofMinutes(d.getDurationMinutes())))) {
                    throw ApiException.badRequest("INVALID_DATES",
                            "The test window must be at least as long as the exam duration.");
                }
                d.setName(req.name().trim());
                d.setDescription(blankToNull(req.description()));
                d.setEndAt(req.endAt());
                if (req.awayWarningThreshold() != null) {
                    d.setAwayWarningThreshold(req.awayWarningThreshold());
                }
            }
        }
        if (req.drivePassword() != null && !req.drivePassword().isBlank()) {
            d.setPasswordHash(hashDrivePassword(req.drivePassword()));
        }
        d.setUpdatedAt(now);
        audit.admin(actor, "DRIVE_UPDATED", "RecruitmentDrive", d.getId(), "code=" + d.getCode()
                + (req.drivePassword() != null && !req.drivePassword().isBlank() ? " (password changed)" : ""), ip);
        return toDto(d, loadStats());
    }

    @Transactional
    public DriveDto setPassword(Long id, String password, AdminPrincipal actor, String ip) {
        RecruitmentDrive d = find(id);
        if (d.getStatus() == DriveStatus.CLOSED) {
            throw ApiException.conflict("DRIVE_CLOSED", "A closed drive cannot be changed.");
        }
        d.setPasswordHash(hashDrivePassword(password));
        d.setUpdatedAt(clock.instant());
        audit.admin(actor, "DRIVE_PASSWORD_CHANGED", "RecruitmentDrive", d.getId(), "code=" + d.getCode(), ip);
        return toDto(d, loadStats());
    }

    @Transactional(readOnly = true)
    public AvailabilityDto availability(Long id) {
        RecruitmentDrive d = find(id);
        return availability(d.getEasyCount(), d.getModerateCount(), d.getHardCount(), d.isSectionBalanced());
    }

    @Transactional(readOnly = true)
    public AvailabilityDto availability(int easy, int moderate, int hard, boolean balanced) {
        int[][] matrix = questionBank.eligibleMatrix();
        int[] need = new int[Difficulty.values().length];
        need[Difficulty.EASY.ordinal()] = easy;
        need[Difficulty.MODERATE.ordinal()] = moderate;
        need[Difficulty.HARD.ordinal()] = hard;
        PaperAllocator.Feasibility f = PaperAllocator.check(matrix, need, balanced);
        List<CellDto> cells = new ArrayList<>();
        long total = 0;
        for (Section s : Section.values()) {
            for (Difficulty df : Difficulty.values()) {
                int avail = matrix[s.ordinal()][df.ordinal()];
                total += avail;
                Integer planned = f.examplePlan() == null ? null : f.examplePlan()[s.ordinal()][df.ordinal()];
                cells.add(new CellDto(s.name(), s.getLabel(), df.name(), avail, planned));
            }
        }
        return new AvailabilityDto(f.feasible(), f.problems(), cells, total, easy + moderate + hard,
                questionBank.countEligibleNeedingReview());
    }

    @Transactional
    public ActivationResultDto activate(Long id, AdminPrincipal actor, String ip) {
        RecruitmentDrive d = find(id);
        Instant now = clock.instant();
        if (d.getStatus() != DriveStatus.DRAFT) {
            throw ApiException.conflict("INVALID_STATE", "Only a DRAFT drive can be activated.");
        }
        List<String> problems = new ArrayList<>();
        if (!d.hasPassword()) {
            problems.add("Set a drive password before activating.");
        }
        if (!d.getEndAt().isAfter(now)) {
            problems.add("The end time has already passed. Edit the schedule first.");
        }
        AvailabilityDto availability = availability(d.getEasyCount(), d.getModerateCount(), d.getHardCount(),
                d.isSectionBalanced());
        if (!availability.feasible()) {
            problems.addAll(availability.problems());
        }
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVATION_BLOCKED", String.join(" ", problems), problems);
        }
        List<String> warnings = new ArrayList<>();
        if (authorizations.countByDriveIdAndStatus(d.getId(), AuthorizationStatus.AUTHORIZED) == 0) {
            warnings.add("No candidates are authorised yet. Add candidate emails before sharing the drive code.");
        }
        if (availability.needsReviewInPool() > 0) {
            warnings.add(availability.needsReviewInPool() + " eligible question(s) are still marked 'Needs SME review' "
                    + "in the workbook. Approve or flag them in the Question Bank before a live drive.");
        }
        if (!d.getStartAt().isAfter(now)) {
            warnings.add("The start time has already passed, so the drive is open to authorised candidates now.");
        }
        d.setStatus(DriveStatus.ACTIVE);
        d.setActivatedAt(now);
        d.setUpdatedAt(now);
        audit.admin(actor, "DRIVE_ACTIVATED", "RecruitmentDrive", d.getId(), "code=" + d.getCode(), ip);
        return new ActivationResultDto(toDto(d, loadStats()), warnings);
    }

    @Transactional
    public DriveDto deactivate(Long id, AdminPrincipal actor, String ip) {
        RecruitmentDrive d = find(id);
        if (d.getStatus() != DriveStatus.ACTIVE) {
            throw ApiException.conflict("INVALID_STATE", "Only an ACTIVE drive can be moved back to draft.");
        }
        if (attempts.existsByDriveId(d.getId())) {
            throw ApiException.conflict("ATTEMPTS_EXIST",
                    "Candidates have already started this drive, so it cannot go back to draft. Close it instead.");
        }
        d.setStatus(DriveStatus.DRAFT);
        d.setActivatedAt(null);
        d.setUpdatedAt(clock.instant());
        audit.admin(actor, "DRIVE_DEACTIVATED", "RecruitmentDrive", d.getId(), "code=" + d.getCode(), ip);
        return toDto(d, loadStats());
    }

    @Transactional
    public DriveDto close(Long id, AdminPrincipal actor, String ip) {
        RecruitmentDrive d = find(id);
        if (d.getStatus() == DriveStatus.CLOSED) {
            throw ApiException.conflict("INVALID_STATE", "This drive is already closed.");
        }
        Instant now = clock.instant();
        d.setStatus(DriveStatus.CLOSED);
        d.setClosedAt(now);
        d.setUpdatedAt(now);
        audit.admin(actor, "DRIVE_CLOSED", "RecruitmentDrive", d.getId(), "code=" + d.getCode()
                + "; attempts in progress continue to their own deadline", ip);
        return toDto(d, loadStats());
    }

    public RecruitmentDrive find(Long id) {
        return drives.findById(id).orElseThrow(() -> ApiException.notFound("Drive"));
    }

    // ------------------------------------------------------------------ helpers

    private void validateConfig(DriveRequest req, Instant now, boolean requireFutureEnd) {
        List<String> problems = new ArrayList<>();
        if (!req.endAt().isAfter(req.startAt())) {
            problems.add("The end time must be after the start time.");
        } else if (req.endAt().isBefore(req.startAt().plus(Duration.ofMinutes(req.durationMinutes())))) {
            problems.add("The test window (start to end) must be at least as long as the exam duration ("
                    + req.durationMinutes() + " minutes).");
        }
        if (requireFutureEnd && !req.endAt().isAfter(now)) {
            problems.add("The end time must be in the future.");
        }
        int total = req.easyCount() + req.moderateCount() + req.hardCount();
        if (total < 1 || total > 100) {
            problems.add("The paper must have between 1 and 100 questions (easy + moderate + hard).");
        }
        boolean negative = Boolean.TRUE.equals(req.negativeMarkingEnabled());
        BigDecimal neg = req.negativeMarks() == null ? BigDecimal.ZERO : req.negativeMarks();
        if (negative && neg.signum() <= 0) {
            problems.add("Enter the marks deducted per incorrect answer, or turn negative marking off.");
        }
        if (negative && neg.compareTo(req.marksPerCorrect()) > 0) {
            problems.add("The negative mark cannot exceed the mark for a correct answer.");
        }
        if (req.drivePassword() != null && !req.drivePassword().isBlank()) {
            problems.addAll(PasswordPolicy.checkDrivePassword(req.drivePassword()));
        }
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DRIVE", String.join(" ", problems), problems);
        }
    }

    private void applyAll(RecruitmentDrive d, DriveRequest req) {
        d.setName(req.name().trim());
        d.setDescription(blankToNull(req.description()));
        d.setStartAt(req.startAt());
        d.setEndAt(req.endAt());
        d.setDurationMinutes(req.durationMinutes());
        d.setEasyCount(req.easyCount());
        d.setModerateCount(req.moderateCount());
        d.setHardCount(req.hardCount());
        d.setSectionBalanced(req.sectionBalanced() == null || req.sectionBalanced());
        d.setShuffleOptions(req.shuffleOptions() == null || req.shuffleOptions());
        d.setMarksPerCorrect(req.marksPerCorrect());
        boolean negative = Boolean.TRUE.equals(req.negativeMarkingEnabled());
        d.setNegativeMarkingEnabled(negative);
        d.setNegativeMarks(negative && req.negativeMarks() != null ? req.negativeMarks() : BigDecimal.ZERO);
        d.setAwayWarningThreshold(req.awayWarningThreshold() == null ? 3 : req.awayWarningThreshold());
    }

    private List<String> lockedFieldChanges(RecruitmentDrive d, DriveRequest req) {
        List<String> changed = new ArrayList<>();
        if (!normaliseCode(req.code()).equals(d.getCode())) changed.add("code");
        if (!Objects.equals(req.startAt(), d.getStartAt())) changed.add("start time");
        if (req.durationMinutes() != d.getDurationMinutes()) changed.add("duration");
        if (req.easyCount() != d.getEasyCount() || req.moderateCount() != d.getModerateCount()
                || req.hardCount() != d.getHardCount()) changed.add("difficulty mix");
        if (req.sectionBalanced() != null && req.sectionBalanced() != d.isSectionBalanced()) changed.add("section balancing");
        if (req.shuffleOptions() != null && req.shuffleOptions() != d.isShuffleOptions()) changed.add("option shuffling");
        if (req.marksPerCorrect().compareTo(d.getMarksPerCorrect()) != 0) changed.add("marks per correct answer");
        boolean negative = Boolean.TRUE.equals(req.negativeMarkingEnabled());
        if (negative != d.isNegativeMarkingEnabled()) changed.add("negative marking");
        if (negative && req.negativeMarks() != null && req.negativeMarks().compareTo(d.getNegativeMarks()) != 0) {
            changed.add("negative marks");
        }
        return changed;
    }

    private String hashDrivePassword(String password) {
        List<String> problems = PasswordPolicy.checkDrivePassword(password);
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", problems.get(0), problems);
        }
        return passwordEncoder.encode(password);
    }

    static String normaliseCode(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw ApiException.badRequest("INVALID_DRIVE_CODE",
                    "Drive code must be 4-20 characters: letters, digits and hyphens (for example RBT-2026-OCT).");
        }
        return code;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private record Stats(Map<Long, Long> authorized, Map<Long, Map<AttemptStatus, Long>> attempts) {
        long authorized(Long id) {
            return authorized.getOrDefault(id, 0L);
        }

        long attempts(Long id, AttemptStatus status) {
            return attempts.getOrDefault(id, Map.of()).getOrDefault(status, 0L);
        }
    }

    private Stats loadStats() {
        Map<Long, Long> auth = new HashMap<>();
        for (Object[] row : authorizations.countByDrive(AuthorizationStatus.AUTHORIZED)) {
            auth.put((Long) row[0], ((Number) row[1]).longValue());
        }
        Map<Long, Map<AttemptStatus, Long>> att = new HashMap<>();
        for (Object[] row : attempts.countByDriveAndStatus()) {
            att.computeIfAbsent((Long) row[0], k -> new HashMap<>())
                    .put((AttemptStatus) row[1], ((Number) row[2]).longValue());
        }
        return new Stats(auth, att);
    }

    private DriveDto toDto(RecruitmentDrive d, Stats s) {
        Instant now = clock.instant();
        DrivePhase phase = d.phaseAt(now);
        long inProgress = s.attempts(d.getId(), AttemptStatus.IN_PROGRESS);
        long completed = s.attempts(d.getId(), AttemptStatus.SUBMITTED) + s.attempts(d.getId(), AttemptStatus.TIMED_OUT);
        return new DriveDto(d.getId(), d.getName(), d.getCode(), d.getDescription(), d.getStartAt(), d.getEndAt(),
                d.getStatus().name(), phase.name(), d.getDurationMinutes(), d.getEasyCount(), d.getModerateCount(),
                d.getHardCount(), d.getTotalQuestions(), d.isSectionBalanced(), d.isShuffleOptions(),
                d.getMarksPerCorrect(), d.getTotalMarks(), d.isNegativeMarkingEnabled(), d.getNegativeMarks(),
                d.getAwayWarningThreshold(), d.hasPassword(), d.getCreatedBy().getUsername(), d.getCreatedAt(),
                d.getUpdatedAt(), d.getActivatedAt(), d.getClosedAt(), s.authorized(d.getId()),
                inProgress + completed, completed, inProgress);
    }
}

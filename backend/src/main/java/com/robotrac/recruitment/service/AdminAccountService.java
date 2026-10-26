package com.robotrac.recruitment.service;

import com.robotrac.recruitment.core.PasswordPolicy;
import com.robotrac.recruitment.domain.ActorType;
import com.robotrac.recruitment.domain.AdminUser;
import com.robotrac.recruitment.exception.ApiException;
import com.robotrac.recruitment.repository.AdminUserRepository;
import com.robotrac.recruitment.security.AdminPrincipal;
import com.robotrac.recruitment.web.dto.AuthDtos.AdminLoginRequest;
import com.robotrac.recruitment.web.dto.AuthDtos.AdminUserDto;
import com.robotrac.recruitment.web.dto.AuthDtos.ChangePasswordRequest;
import com.robotrac.recruitment.web.dto.AuthDtos.CreateAdminRequest;
import com.robotrac.recruitment.web.dto.AuthDtos.SetupAdminRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HR/Admin accounts.
 *
 * <h2>Secure first-admin setup</h2>
 * There is no default admin password anywhere. When the application starts with an empty admin table it
 * generates a random one-time setup token and prints it to the server console only. Whoever can read the
 * server console opens /admin-setup.html, enters the token and chooses the first admin's username and a
 * strong password. The token is consumed immediately and the setup endpoint is permanently disabled once
 * any admin exists.
 */
@Service
public class AdminAccountService {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);
    private static final String SCOPE = "admin-login";

    private final AdminUserRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottle throttle;
    private final AuditService audit;
    private final SecureRandom random;
    private final Clock clock;
    private final AtomicReference<String> setupToken = new AtomicReference<>();
    private final String timingDummyHash;

    public AdminAccountService(AdminUserRepository adminRepository, PasswordEncoder passwordEncoder,
                               LoginThrottle throttle, AuditService audit, SecureRandom random, Clock clock) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.throttle = throttle;
        this.audit = audit;
        this.random = random;
        this.clock = clock;
        this.timingDummyHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prepareSetupTokenIfNeeded() {
        if (adminRepository.count() == 0) {
            String token = newSetupToken();
            log.warn("""

                    ==================================================================
                     ROBOTRAC FIRST-TIME SETUP - no HR/Admin account exists yet.
                     Open  /admin-setup.html  and enter this one-time setup token:

                         {}

                     The token works once and only until the first admin is created.
                     Restarting the server generates a new token.
                    ==================================================================
                    """, token);
        }
    }

    /** Generates (or replaces) the in-memory one-time token. Visible for tests. */
    public String newSetupToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        setupToken.set(token);
        return token;
    }

    @Transactional(readOnly = true)
    public boolean isSetupRequired() {
        return adminRepository.count() == 0;
    }

    @Transactional
    public AdminUserDto createFirstAdmin(SetupAdminRequest req, String ip) {
        throttle.checkAllowed("setup", "setup", ip);
        if (adminRepository.count() > 0) {
            throw ApiException.conflict("SETUP_ALREADY_DONE", "Setup is already complete. Please use the HR login page.");
        }
        String expected = setupToken.get();
        if (expected == null || !constantTimeEquals(expected, req.setupToken().trim())) {
            throttle.recordFailure("setup", "setup", ip);
            audit.record(ActorType.ANONYMOUS, null, null, "ADMIN_SETUP_FAILED", "AdminUser", null,
                    "Invalid setup token", ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SETUP_TOKEN",
                    "The setup token is not valid. Copy it exactly from the server console.");
        }
        requireStrongPassword(req.password());
        // Consume the token atomically so two simultaneous requests cannot both succeed.
        if (!setupToken.compareAndSet(expected, null)) {
            throw ApiException.conflict("SETUP_ALREADY_DONE", "Setup is already complete.");
        }
        AdminUser admin = adminRepository.save(new AdminUser(req.username().trim(), req.fullName().trim(),
                passwordEncoder.encode(req.password()), clock.instant()));
        audit.record(ActorType.SYSTEM, admin.getId(), admin.getUsername(), "ADMIN_SETUP_COMPLETED", "AdminUser",
                admin.getId(), "First administrator created", ip);
        log.info("First administrator '{}' created; setup endpoint disabled.", admin.getUsername());
        return toDto(admin);
    }

    @Transactional
    public AdminPrincipal login(AdminLoginRequest req, String ip) {
        String username = req.username().trim();
        throttle.checkAllowed(SCOPE, username, ip);
        AdminUser admin = adminRepository.findByUsernameIgnoreCase(username).orElse(null);
        boolean ok = passwordEncoder.matches(req.password(), admin != null ? admin.getPasswordHash() : timingDummyHash);
        if (admin == null || !ok || !admin.isEnabled()) {
            throttle.recordFailure(SCOPE, username, ip);
            audit.record(ActorType.ANONYMOUS, null, username, "ADMIN_LOGIN_FAILED", "AdminUser", null,
                    "Invalid username or password", ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password.");
        }
        throttle.recordSuccess(SCOPE, username);
        admin.setLastLoginAt(clock.instant());
        audit.record(ActorType.ADMIN, admin.getId(), admin.getUsername(), "ADMIN_LOGIN", "AdminUser",
                admin.getId(), null, ip);
        return new AdminPrincipal(admin.getId(), admin.getUsername(), admin.getFullName());
    }

    @Transactional(readOnly = true)
    public List<AdminUserDto> listUsers() {
        return adminRepository.findAll(Sort.by("username")).stream().map(AdminAccountService::toDto).toList();
    }

    @Transactional
    public AdminUserDto createUser(CreateAdminRequest req, AdminPrincipal actor, String ip) {
        String username = req.username().trim();
        if (adminRepository.existsByUsernameIgnoreCase(username)) {
            throw ApiException.conflict("DUPLICATE_USERNAME", "That username is already in use.");
        }
        requireStrongPassword(req.password());
        AdminUser created = adminRepository.save(new AdminUser(username, req.fullName().trim(),
                passwordEncoder.encode(req.password()), clock.instant()));
        audit.admin(actor, "ADMIN_USER_CREATED", "AdminUser", created.getId(), "username=" + username, ip);
        return toDto(created);
    }

    @Transactional
    public void changeOwnPassword(AdminPrincipal actor, ChangePasswordRequest req, String ip) {
        AdminUser admin = adminRepository.findById(actor.adminId()).orElseThrow(() -> ApiException.notFound("Account"));
        if (!passwordEncoder.matches(req.currentPassword(), admin.getPasswordHash())) {
            throw ApiException.badRequest("INVALID_CURRENT_PASSWORD", "Your current password is not correct.");
        }
        requireStrongPassword(req.newPassword());
        admin.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        audit.admin(actor, "ADMIN_PASSWORD_CHANGED", "AdminUser", admin.getId(), null, ip);
    }

    private void requireStrongPassword(String password) {
        List<String> problems = PasswordPolicy.checkAdminPassword(password);
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", problems.get(0), problems);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static AdminUserDto toDto(AdminUser a) {
        return new AdminUserDto(a.getId(), a.getUsername(), a.getFullName(), a.isEnabled(), a.getCreatedAt(),
                a.getLastLoginAt());
    }
}

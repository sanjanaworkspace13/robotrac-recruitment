package com.robotrac.recruitment.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Request/response bodies for login, session, CSRF and admin-account endpoints. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record CandidateLoginRequest(
            @NotBlank(message = "Please enter your registered email ID.") @Size(max = 254) String email,
            @NotBlank(message = "Please enter the drive code.") @Size(max = 20) String driveCode,
            @NotBlank(message = "Please enter the drive password.") @Size(max = 64) String drivePassword) {
    }

    /** next = "INSTRUCTIONS" (no attempt yet) or "EXAM" (resume an attempt in progress). */
    public record CandidateLoginResponse(String email, String driveCode, String driveName, String next) {
    }

    public record AdminLoginRequest(
            @NotBlank(message = "Please enter your username.") @Size(max = 60) String username,
            @NotBlank(message = "Please enter your password.") @Size(max = 128) String password) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionInfo(boolean authenticated, String role, String displayName, String email,
                              String driveCode, String driveName) {
        public static SessionInfo anonymous() {
            return new SessionInfo(false, null, null, null, null, null);
        }
    }

    public record CsrfResponse(String headerName, String token) {
    }

    public record SetupStatus(boolean setupRequired) {
    }

    public record SetupAdminRequest(
            @NotBlank(message = "Setup token is required.") @Size(max = 200) String setupToken,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{3,60}$",
                    message = "Username: 3-60 letters, digits, dot, underscore or hyphen.") String username,
            @NotBlank(message = "Full name is required.") @Size(max = 120) String fullName,
            @NotBlank(message = "Password is required.") @Size(max = 128) String password) {
    }

    public record CreateAdminRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{3,60}$",
                    message = "Username: 3-60 letters, digits, dot, underscore or hyphen.") String username,
            @NotBlank(message = "Full name is required.") @Size(max = 120) String fullName,
            @NotBlank(message = "Password is required.") @Size(max = 128) String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "Current password is required.") @Size(max = 128) String currentPassword,
            @NotBlank(message = "New password is required.") @Size(max = 128) String newPassword) {
    }

    public record AdminUserDto(Long id, String username, String fullName, boolean enabled,
                               Instant createdAt, Instant lastLoginAt) {
    }
}

package com.robotrac.recruitment.web.controller;

import com.robotrac.recruitment.security.AdminPrincipal;
import com.robotrac.recruitment.security.CandidatePrincipal;
import com.robotrac.recruitment.security.ClientInfo;
import com.robotrac.recruitment.security.CurrentUser;
import com.robotrac.recruitment.security.SessionLogin;
import com.robotrac.recruitment.service.AdminAccountService;
import com.robotrac.recruitment.service.CandidateAuthService;
import com.robotrac.recruitment.web.dto.AuthDtos.AdminLoginRequest;
import com.robotrac.recruitment.web.dto.AuthDtos.CandidateLoginRequest;
import com.robotrac.recruitment.web.dto.AuthDtos.CandidateLoginResponse;
import com.robotrac.recruitment.web.dto.AuthDtos.CsrfResponse;
import com.robotrac.recruitment.web.dto.AuthDtos.SessionInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication endpoints. Logout is POST /api/auth/logout (handled by Spring Security).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CandidateAuthService candidateAuth;
    private final AdminAccountService adminAccounts;
    private final SessionLogin sessionLogin;

    public AuthController(CandidateAuthService candidateAuth, AdminAccountService adminAccounts, SessionLogin sessionLogin) {
        this.candidateAuth = candidateAuth;
        this.adminAccounts = adminAccounts;
        this.sessionLogin = sessionLogin;
    }

    /** Issues the CSRF token (also set as the XSRF-TOKEN cookie). Send it back in the X-XSRF-TOKEN header. */
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/me")
    public SessionInfo me() {
        Object p = CurrentUser.principal();
        if (p instanceof CandidatePrincipal c) {
            return new SessionInfo(true, "CANDIDATE", c.email(), c.email(), c.driveCode(), candidateAuth.driveName(c));
        }
        if (p instanceof AdminPrincipal a) {
            return new SessionInfo(true, "ADMIN", a.fullName(), null, null, null);
        }
        return SessionInfo.anonymous();
    }

    @PostMapping("/candidate/login")
    public CandidateLoginResponse candidateLogin(@Valid @RequestBody CandidateLoginRequest req,
                                                 HttpServletRequest request, HttpServletResponse response) {
        CandidateAuthService.LoginResult result = candidateAuth.login(req, ClientInfo.ip(request));
        sessionLogin.logIn(result.principal(), SessionLogin.ROLE_CANDIDATE, request, response);
        return result.response();
    }

    @PostMapping("/admin/login")
    public SessionInfo adminLogin(@Valid @RequestBody AdminLoginRequest req,
                                  HttpServletRequest request, HttpServletResponse response) {
        AdminPrincipal principal = adminAccounts.login(req, ClientInfo.ip(request));
        sessionLogin.logIn(principal, SessionLogin.ROLE_ADMIN, request, response);
        return new SessionInfo(true, "ADMIN", principal.fullName(), null, null, null);
    }
}

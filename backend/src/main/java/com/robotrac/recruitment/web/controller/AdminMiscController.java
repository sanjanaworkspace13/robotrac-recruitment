package com.robotrac.recruitment.web.controller;

import com.robotrac.recruitment.security.ClientInfo;
import com.robotrac.recruitment.security.CurrentUser;
import com.robotrac.recruitment.service.AdminAccountService;
import com.robotrac.recruitment.service.AuditService;
import com.robotrac.recruitment.service.DashboardService;
import com.robotrac.recruitment.web.dto.AuthDtos.AdminUserDto;
import com.robotrac.recruitment.web.dto.AuthDtos.ChangePasswordRequest;
import com.robotrac.recruitment.web.dto.AuthDtos.CreateAdminRequest;
import com.robotrac.recruitment.web.dto.PageResponse;
import com.robotrac.recruitment.web.dto.ResultDtos.AuditLogDto;
import com.robotrac.recruitment.web.dto.ResultDtos.DashboardStats;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** HR: dashboard statistics, audit log and HR user accounts (ROLE_ADMIN only). */
@RestController
@RequestMapping("/api/admin")
public class AdminMiscController {

    private final DashboardService dashboard;
    private final AuditService audit;
    private final AdminAccountService accounts;

    public AdminMiscController(DashboardService dashboard, AuditService audit, AdminAccountService accounts) {
        this.dashboard = dashboard;
        this.audit = audit;
        this.accounts = accounts;
    }

    @GetMapping("/dashboard")
    public DashboardStats dashboard() {
        return dashboard.stats();
    }

    @GetMapping("/audit")
    public PageResponse<AuditLogDto> audit(@RequestParam(required = false) String action,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        return audit.search(action, q, page, size);
    }

    @GetMapping("/users")
    public List<AdminUserDto> users() {
        return accounts.listUsers();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUserDto createUser(@Valid @RequestBody CreateAdminRequest req, HttpServletRequest request) {
        return accounts.createUser(req, CurrentUser.admin(), ClientInfo.ip(request));
    }

    @PostMapping("/users/me/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req, HttpServletRequest request) {
        accounts.changeOwnPassword(CurrentUser.admin(), req, ClientInfo.ip(request));
        return ResponseEntity.noContent().build();
    }
}

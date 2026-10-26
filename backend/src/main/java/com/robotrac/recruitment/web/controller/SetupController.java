package com.robotrac.recruitment.web.controller;

import com.robotrac.recruitment.security.ClientInfo;
import com.robotrac.recruitment.service.AdminAccountService;
import com.robotrac.recruitment.web.dto.AuthDtos.AdminUserDto;
import com.robotrac.recruitment.web.dto.AuthDtos.SetupAdminRequest;
import com.robotrac.recruitment.web.dto.AuthDtos.SetupStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** One-time creation of the first HR/Admin account (disabled once any admin exists). */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final AdminAccountService adminAccounts;

    public SetupController(AdminAccountService adminAccounts) {
        this.adminAccounts = adminAccounts;
    }

    @GetMapping("/status")
    public SetupStatus status() {
        return new SetupStatus(adminAccounts.isSetupRequired());
    }

    @PostMapping("/admin")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUserDto createFirstAdmin(@Valid @RequestBody SetupAdminRequest req, HttpServletRequest request) {
        return adminAccounts.createFirstAdmin(req, ClientInfo.ip(request));
    }
}

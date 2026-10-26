package com.robotrac.recruitment.web.controller;

import com.robotrac.recruitment.security.ClientInfo;
import com.robotrac.recruitment.security.CurrentUser;
import com.robotrac.recruitment.service.DriveService;
import com.robotrac.recruitment.web.dto.DriveDtos.ActivationResultDto;
import com.robotrac.recruitment.web.dto.DriveDtos.AvailabilityDto;
import com.robotrac.recruitment.web.dto.DriveDtos.DriveDto;
import com.robotrac.recruitment.web.dto.DriveDtos.DrivePasswordRequest;
import com.robotrac.recruitment.web.dto.DriveDtos.DriveRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** HR: recruitment drives (ROLE_ADMIN only). */
@RestController
@RequestMapping("/api/admin/drives")
public class AdminDriveController {

    private final DriveService drives;

    public AdminDriveController(DriveService drives) {
        this.drives = drives;
    }

    @GetMapping
    public List<DriveDto> list() {
        return drives.list();
    }

    @GetMapping("/{id}")
    public DriveDto get(@PathVariable Long id) {
        return drives.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DriveDto create(@Valid @RequestBody DriveRequest req, HttpServletRequest request) {
        return drives.create(req, CurrentUser.admin(), ClientInfo.ip(request));
    }

    @PutMapping("/{id}")
    public DriveDto update(@PathVariable Long id, @Valid @RequestBody DriveRequest req, HttpServletRequest request) {
        return drives.update(id, req, CurrentUser.admin(), ClientInfo.ip(request));
    }

    @PutMapping("/{id}/password")
    public DriveDto password(@PathVariable Long id, @Valid @RequestBody DrivePasswordRequest req, HttpServletRequest request) {
        return drives.setPassword(id, req.drivePassword(), CurrentUser.admin(), ClientInfo.ip(request));
    }

    @GetMapping("/{id}/availability")
    public AvailabilityDto availability(@PathVariable Long id) {
        return drives.availability(id);
    }

    /** Preview while filling in the drive form (before saving). */
    @GetMapping("/availability-preview")
    public AvailabilityDto preview(@RequestParam @Min(0) @Max(100) int easy, @RequestParam @Min(0) @Max(100) int moderate,
                                   @RequestParam @Min(0) @Max(100) int hard,
                                   @RequestParam(defaultValue = "true") boolean balanced) {
        return drives.availability(easy, moderate, hard, balanced);
    }

    @PostMapping("/{id}/activate")
    public ActivationResultDto activate(@PathVariable Long id, HttpServletRequest request) {
        return drives.activate(id, CurrentUser.admin(), ClientInfo.ip(request));
    }

    @PostMapping("/{id}/deactivate")
    public DriveDto deactivate(@PathVariable Long id, HttpServletRequest request) {
        return drives.deactivate(id, CurrentUser.admin(), ClientInfo.ip(request));
    }

    @PostMapping("/{id}/close")
    public DriveDto close(@PathVariable Long id, HttpServletRequest request) {
        return drives.close(id, CurrentUser.admin(), ClientInfo.ip(request));
    }
}

package com.robotrac.recruitment.web;

import com.robotrac.recruitment.support.IntegrationTestBase;
import com.robotrac.recruitment.web.dto.DriveDtos.DriveDto;
import com.robotrac.recruitment.web.dto.DriveDtos.DriveRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DriveManagementIntegrationTest extends IntegrationTestBase {

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private String body(DriveRequest r) throws Exception {
        return toJson(r);
    }

    @Test
    void hrCanCreateAndActivateADrive() throws Exception {
        MockHttpSession hr = adminLogin();
        String code = "RBT-" + uid();
        String created = mvc.perform(post("/api/admin/drives").with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON)
                        .content(body(driveRequest(code, now().plusSeconds(60), now().plus(Duration.ofHours(2)), 40, 5, 15, 10, DRIVE_PASSWORD))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.passwordSet").value(true))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.totalQuestions").value(30))
                .andReturn().getResponse().getContentAsString();
        Integer id = read(created, "$.id");
        mvc.perform(get("/api/admin/drives/" + id + "/availability").session(hr))
                .andExpect(status().isOk()).andExpect(jsonPath("$.feasible").value(true));
        mvc.perform(post("/api/admin/drives/" + id + "/activate").with(csrf()).session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drive.status").value("ACTIVE"))
                .andExpect(jsonPath("$.warnings").isArray());
    }

    @Test
    void driveCodesAreUniqueIgnoringCase() throws Exception {
        MockHttpSession hr = adminLogin();
        String code = "UNQ-" + uid();
        DriveRequest r = driveRequest(code, now(), now().plus(Duration.ofHours(2)), 40, 5, 15, 10, null);
        mvc.perform(post("/api/admin/drives").with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON).content(body(r)))
                .andExpect(status().isCreated());
        DriveRequest dup = driveRequest(code.toLowerCase(), now(), now().plus(Duration.ofHours(2)), 40, 5, 15, 10, null);
        mvc.perform(post("/api/admin/drives").with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON).content(body(dup)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DRIVE_CODE"));
    }

    @Test
    void invalidDatesAreRejected() throws Exception {
        MockHttpSession hr = adminLogin();
        DriveRequest endBeforeStart = driveRequest("DT-" + uid(), now().plus(Duration.ofHours(3)), now().plus(Duration.ofHours(1)),
                40, 5, 15, 10, null);
        mvc.perform(post("/api/admin/drives").with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON).content(body(endBeforeStart)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DRIVE"));
        DriveRequest windowTooShort = driveRequest("DT-" + uid(), now(), now().plus(Duration.ofMinutes(30)), 40, 5, 15, 10, null);
        mvc.perform(post("/api/admin/drives").with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON).content(body(windowTooShort)))
                .andExpect(status().isBadRequest());
        DriveRequest inThePast = driveRequest("DT-" + uid(), now().minus(Duration.ofHours(5)), now().minus(Duration.ofHours(1)),
                40, 5, 15, 10, null);
        mvc.perform(post("/api/admin/drives").with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON).content(body(inThePast)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void activationIsBlockedWhenTheBankCannotSupplyTheMix() throws Exception {
        MockHttpSession hr = adminLogin();
        DriveDto d = driveService.create(driveRequest("BIG-" + uid(), now(), now().plus(Duration.ofHours(2)), 40, 5, 15, 40,
                DRIVE_PASSWORD), admin(), "127.0.0.1");
        mvc.perform(post("/api/admin/drives/" + d.id() + "/activate").with(csrf()).session(hr))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVATION_BLOCKED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("HARD")));
    }

    @Test
    void activationNeedsADrivePassword() throws Exception {
        MockHttpSession hr = adminLogin();
        DriveDto d = driveService.create(driveRequest("NOPW-" + uid(), now(), now().plus(Duration.ofHours(2)), 40, 5, 15, 10,
                null), admin(), "127.0.0.1");
        mvc.perform(post("/api/admin/drives/" + d.id() + "/activate").with(csrf()).session(hr))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTIVATION_BLOCKED"));
        mvc.perform(put("/api/admin/drives/" + d.id() + "/password").with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON)
                .content("{\"drivePassword\":\"weak\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/drives/" + d.id() + "/password").with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON)
                .content("{\"drivePassword\":\"" + DRIVE_PASSWORD + "\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/admin/drives/" + d.id() + "/activate").with(csrf()).session(hr)).andExpect(status().isOk());
    }

    @Test
    void activeDriveLocksExamSettings() throws Exception {
        MockHttpSession hr = adminLogin();
        DriveDto d = liveDrive();
        DriveRequest changedMix = driveRequest(d.code(), d.startAt(), d.endAt(), 40, 5, 15, 9, null);
        mvc.perform(put("/api/admin/drives/" + d.id()).with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON).content(body(changedMix)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRIVE_ACTIVE_LOCKED"));
        DriveRequest extendEnd = driveRequest(d.code(), d.startAt(), d.endAt().plus(Duration.ofHours(1)), 40, 5, 15, 10, null);
        mvc.perform(put("/api/admin/drives/" + d.id()).with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON).content(body(extendEnd)))
                .andExpect(status().isOk());
    }

    @Test
    void closedDriveRestrictions() throws Exception {
        MockHttpSession hr = adminLogin();
        DriveDto d = liveDrive();
        String email = email();
        authorize(d.id(), email);
        mvc.perform(post("/api/admin/drives/" + d.id() + "/close").with(csrf()).session(hr))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        DriveRequest edit = driveRequest(d.code(), d.startAt(), d.endAt(), 40, 5, 15, 10, null);
        mvc.perform(put("/api/admin/drives/" + d.id()).with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON).content(body(edit)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/drives/" + d.id() + "/candidates").with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email() + "\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/admin/drives/" + d.id() + "/activate").with(csrf()).session(hr)).andExpect(status().isConflict());
        candidateLoginRaw(email, d.code(), DRIVE_PASSWORD)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRIVE_NOT_ACTIVE"));
    }

    @Test
    void deactivateOnlyBeforeAnyAttempt() throws Exception {
        MockHttpSession hr = adminLogin();
        Candidate c = liveCandidate();
        startAttempt(c.session());
        mvc.perform(post("/api/admin/drives/" + c.drive().id() + "/deactivate").with(csrf()).session(hr))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTEMPTS_EXIST"));
        DriveDto fresh = liveDrive();
        mvc.perform(post("/api/admin/drives/" + fresh.id() + "/deactivate").with(csrf()).session(hr))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void candidateAuthorisationRules() throws Exception {
        MockHttpSession hr = adminLogin();
        DriveDto d = liveDrive();
        String base = "/api/admin/drives/" + d.id() + "/candidates";
        String a = email();
        mvc.perform(post(base).with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + a + "\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post(base).with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + a.toUpperCase() + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_AUTHORIZED"));
        mvc.perform(post(base).with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());

        String b = email();
        String bulk = "email,full name\\n" + b + ",Bina Rao\\n" + b + "\\nbroken@\\n" + a + "\\n";
        mvc.perform(post(base + "/bulk").with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + bulk + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.alreadyAuthorized").value(1))
                .andExpect(jsonPath("$.invalid.length()").value(2));

        // Revoke b (no attempt yet) -> b can no longer log in.
        String list = mvc.perform(get(base).param("q", b).session(hr)).andReturn().getResponse().getContentAsString();
        Integer authId = read(list, "$.items[0].authorizationId");
        mvc.perform(delete(base + "/" + authId).with(csrf()).session(hr)).andExpect(status().isNoContent());
        candidateLoginRaw(b, d.code(), DRIVE_PASSWORD).andExpect(status().isUnauthorized());

        // a starts the test -> a's authorisation can no longer be removed.
        MockHttpSession sa = candidateLogin(a, d.code());
        startAttempt(sa);
        String listA = mvc.perform(get(base).param("q", a).session(hr)).andReturn().getResponse().getContentAsString();
        Integer authA = read(listA, "$.items[0].authorizationId");
        mvc.perform(delete(base + "/" + authA).with(csrf()).session(hr))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTEMPT_EXISTS"));
    }
}

package com.robotrac.recruitment.web;

import com.robotrac.recruitment.support.IntegrationTestBase;
import com.robotrac.recruitment.web.dto.DriveDtos.DriveDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthAndAccessIntegrationTest extends IntegrationTestBase {

    @Test
    void healthEndpointReportsApplicationAndDatabase() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"));
    }

    @Test
    void validCandidateLogin() throws Exception {
        DriveDto drive = liveDrive();
        String email = email();
        authorize(drive.id(), email);
        // Email typed with spaces and capitals is normalised; the code is case-insensitive.
        candidateLoginRaw("  " + email.toUpperCase() + " ", drive.code().toLowerCase(), DRIVE_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("INSTRUCTIONS"))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void wrongPasswordIsRejectedGenerically() throws Exception {
        DriveDto drive = liveDrive();
        String email = email();
        authorize(drive.id(), email);
        candidateLoginRaw(email, drive.code(), "WrongPass123")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void unauthorisedEmailCannotLoginEvenWithCorrectCodeAndPassword() throws Exception {
        DriveDto drive = liveDrive();
        candidateLoginRaw(email(), drive.code(), DRIVE_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void unknownDriveCodeGivesTheSameGenericError() throws Exception {
        candidateLoginRaw(email(), "NOPE-" + uid(), DRIVE_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void inactiveDraftDriveIsRefused() throws Exception {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        DriveDto draft = driveService.create(driveRequest("D-" + uid(), now.minusSeconds(60), now.plus(Duration.ofHours(2)),
                40, 5, 15, 10, DRIVE_PASSWORD), admin(), "127.0.0.1");
        String email = email();
        authorize(draft.id(), email);
        candidateLoginRaw(email, draft.code(), DRIVE_PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRIVE_NOT_ACTIVE"));
    }

    @Test
    void driveNotStartedYetIsRefused() throws Exception {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        DriveDto d = driveService.create(driveRequest("S-" + uid(), now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(3)),
                40, 5, 15, 10, DRIVE_PASSWORD), admin(), "127.0.0.1");
        driveService.activate(d.id(), admin(), "127.0.0.1");
        String email = email();
        authorize(d.id(), email);
        candidateLoginRaw(email, d.code(), DRIVE_PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRIVE_NOT_STARTED"));
    }

    @Test
    void expiredDriveIsRefused() throws Exception {
        DriveDto drive = liveDrive();
        String email = email();
        authorize(drive.id(), email);
        clock.advance(Duration.ofHours(4)); // past the 3-hour window
        candidateLoginRaw(email, drive.code(), DRIVE_PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRIVE_ENDED"));
    }

    @Test
    void adminEndpointsNeedAuthentication() throws Exception {
        mvc.perform(get("/api/admin/dashboard"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void candidateCannotUseAdminEndpoints() throws Exception {
        Candidate c = liveCandidate();
        mvc.perform(get("/api/admin/dashboard").session(c.session())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/results").session(c.session())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/questions").session(c.session())).andExpect(status().isForbidden());
    }

    @Test
    void adminCannotUseCandidateEndpoints() throws Exception {
        MockHttpSession admin = adminLogin();
        mvc.perform(get("/api/candidate/attempt").session(admin)).andExpect(status().isForbidden());
    }

    @Test
    void candidateCannotReachAnotherCandidatesAttempt() throws Exception {
        Candidate a = liveCandidate();
        startAttempt(a.session());
        Long attemptIdOfA = attempts.findAll().stream()
                .filter(x -> x.getDrive().getId().equals(a.drive().id())).findFirst().orElseThrow().getId();

        String emailB = email();
        authorize(a.drive().id(), emailB);
        MockHttpSession b = candidateLogin(emailB, a.drive().code());

        // B's own state: no attempt yet (B never sees A's attempt; candidate URLs contain no ids).
        mvc.perform(get("/api/candidate/attempt").session(b))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_ATTEMPT"));
        // Guessing A's attempt id on the HR API is blocked.
        mvc.perform(get("/api/admin/results/" + attemptIdOfA).session(b)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/results/" + attemptIdOfA + "/events").session(b)).andExpect(status().isForbidden());
    }

    @Test
    void adminLoginRejectsBadPasswordAndAcceptsGoodOne() throws Exception {
        admin();
        mvc.perform(post("/api/auth/admin/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + ADMIN_USERNAME + "\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
        MockHttpSession session = adminLogin();
        mvc.perform(get("/api/admin/dashboard").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void postsWithoutCsrfTokenAreRejected() throws Exception {
        mvc.perform(post("/api/auth/candidate/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"driveCode\":\"ABCD\",\"drivePassword\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void csrfEndpointIssuesToken() throws Exception {
        String body = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) read(body, "$.headerName")).isEqualTo("X-XSRF-TOKEN");
        assertThat((String) read(body, "$.token")).isNotBlank();
    }

    @Test
    void validationErrorsUseTheStandardErrorShape() throws Exception {
        mvc.perform(post("/api/auth/candidate/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"driveCode\":\"\",\"drivePassword\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }
}

package com.robotrac.recruitment.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.robotrac.recruitment.domain.ActorType;
import com.robotrac.recruitment.domain.AdminUser;
import com.robotrac.recruitment.domain.ExamAttempt;
import com.robotrac.recruitment.repository.AdminUserRepository;
import com.robotrac.recruitment.repository.AttemptQuestionRepository;
import com.robotrac.recruitment.repository.CandidateRepository;
import com.robotrac.recruitment.repository.ExamAttemptRepository;
import com.robotrac.recruitment.repository.ExamResultRepository;
import com.robotrac.recruitment.repository.MonitoringEventRepository;
import com.robotrac.recruitment.repository.QuestionRepository;
import com.robotrac.recruitment.security.AdminPrincipal;
import com.robotrac.recruitment.service.CandidateAuthorizationService;
import com.robotrac.recruitment.service.DriveService;
import com.robotrac.recruitment.service.QuestionBankService;
import com.robotrac.recruitment.web.dto.DriveDtos.DriveDto;
import com.robotrac.recruitment.web.dto.DriveDtos.DriveRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared setup for integration tests: full application context, in-memory H2 database (MySQL mode, Flyway
 * migrations applied), MockMvc with real Spring Security, and a controllable clock.
 * Tests use unique drive codes and emails so they never interfere with each other.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTestBase.TestBeans.class)
public abstract class IntegrationTestBase {

    public static final String ADMIN_USERNAME = "hr.tester";
    public static final String ADMIN_PASSWORD = "Str0ng!Passw0rd";
    public static final String DRIVE_PASSWORD = "Drive2026pass";

    @TestConfiguration
    public static class TestBeans {
        @Bean
        @Primary
        public MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected MutableClock clock;
    @Autowired protected AdminUserRepository adminUsers;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected QuestionBankService questionBank;
    @Autowired protected QuestionRepository questions;
    @Autowired protected DriveService driveService;
    @Autowired protected CandidateAuthorizationService authorizationService;
    @Autowired protected ExamAttemptRepository attempts;
    @Autowired protected AttemptQuestionRepository attemptQuestions;
    @Autowired protected ExamResultRepository results;
    @Autowired protected MonitoringEventRepository events;
    @Autowired protected CandidateRepository candidates;

    @BeforeEach
    void baseSetUp() throws Exception {
        clock.reset();
        ensureQuestionBank();
    }

    /** Imports the real HR workbook (src/test/resources/question-bank.xlsx) once per test database. */
    protected void ensureQuestionBank() throws Exception {
        synchronized (IntegrationTestBase.class) {
            if (questions.count() == 0) {
                try (InputStream in = getClass().getResourceAsStream("/question-bank.xlsx")) {
                    questionBank.importWorkbook(in, "question-bank.xlsx", ActorType.SYSTEM, null, "test", null);
                }
            }
        }
    }

    protected AdminPrincipal admin() {
        AdminUser a = adminUsers.findByUsernameIgnoreCase(ADMIN_USERNAME).orElseGet(() -> adminUsers.save(
                new AdminUser(ADMIN_USERNAME, "HR Tester", passwordEncoder.encode(ADMIN_PASSWORD), Instant.now())));
        return new AdminPrincipal(a.getId(), a.getUsername(), a.getFullName());
    }

    protected static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    protected static String email() {
        return "cand." + uid().toLowerCase() + "@example.com";
    }

    protected DriveRequest driveRequest(String code, Instant start, Instant end, int duration,
                                        int easy, int moderate, int hard, String password) {
        return new DriveRequest("Test drive " + code, code, "Integration test drive", start, end, duration,
                easy, moderate, hard, true, true, BigDecimal.ONE, false, BigDecimal.ZERO, 3, password);
    }

    /** A drive that is open now: window started a minute ago, ends in 3 hours, 40 minutes, 5/15/10. */
    protected DriveDto liveDrive() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        DriveDto d = driveService.create(driveRequest("T-" + uid(), now.minus(Duration.ofMinutes(1)),
                now.plus(Duration.ofHours(3)), 40, 5, 15, 10, DRIVE_PASSWORD), admin(), "127.0.0.1");
        return driveService.activate(d.id(), admin(), "127.0.0.1").drive();
    }

    protected void authorize(Long driveId, String email) {
        authorizationService.addOne(driveId, email, null, admin(), "127.0.0.1");
    }

    protected ResultActions candidateLoginRaw(String email, String code, String password) throws Exception {
        return mvc.perform(post("/api/auth/candidate/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"driveCode\":\"" + code + "\",\"drivePassword\":\"" + password + "\"}"));
    }

    protected MockHttpSession candidateLogin(String email, String code) throws Exception {
        MvcResult r = candidateLoginRaw(email, code, DRIVE_PASSWORD).andExpect(status().isOk()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    protected MockHttpSession adminLogin() throws Exception {
        admin();
        MvcResult r = mvc.perform(post("/api/auth/admin/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + ADMIN_USERNAME + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    /** Live drive + one authorised, logged-in candidate. */
    protected Candidate liveCandidate() throws Exception {
        DriveDto drive = liveDrive();
        String email = email();
        authorize(drive.id(), email);
        return new Candidate(drive, email, candidateLogin(email, drive.code()));
    }

    public record Candidate(DriveDto drive, String email, MockHttpSession session) {
    }

    protected String startAttempt(MockHttpSession session) throws Exception {
        return mvc.perform(post("/api/candidate/attempt/start").with(csrf()).session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    protected static <T> T read(String jsonText, String path) {
        return JsonPath.read(jsonText, path);
    }

    /** Freshly loaded attempt of a candidate (null if none). */
    protected ExamAttempt attemptOf(Candidate c) {
        Long candidateId = candidates.findByEmail(c.email()).orElseThrow().getId();
        return attempts.findByDriveIdAndCandidateId(c.drive().id(), candidateId).orElse(null);
    }

    /** Every field name anywhere in a JSON document (used to prove secrets are absent). */
    protected Set<String> jsonKeys(String body) throws Exception {
        Set<String> keys = new HashSet<>();
        collectKeys(json.readTree(body), keys);
        return keys;
    }

    private static void collectKeys(com.fasterxml.jackson.databind.JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String n = names.next();
                keys.add(n);
                collectKeys(node.get(n), keys);
            }
        } else if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                collectKeys(child, keys);
            }
        }
    }

    protected String toJson(Object o) throws Exception {
        return json.writeValueAsString(o);
    }
}

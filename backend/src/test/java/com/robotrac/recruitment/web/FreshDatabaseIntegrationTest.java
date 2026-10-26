package com.robotrac.recruitment.web;

import com.robotrac.recruitment.service.AdminAccountService;
import com.robotrac.recruitment.support.IntegrationTestBase;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs against its OWN empty database (the extra property gives it a separate application context), to
 * test the first-admin setup and the HR workbook upload from scratch, in order.
 */
@SpringBootTest(properties = "test.context=fresh-database")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTestBase.TestBeans.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FreshDatabaseIntegrationTest {

    private static final String USER = "first.admin";
    private static final String PASSWORD = "Fir5t!Admin-Pass";

    @Autowired private MockMvc mvc;
    @Autowired private AdminAccountService accounts;

    @Test
    @Order(1)
    void firstAdminSetupIsOneTimeAndTokenProtected() throws Exception {
        mvc.perform(get("/api/setup/status")).andExpect(jsonPath("$.setupRequired").value(true));
        String token = accounts.newSetupToken(); // the same kind of token that is printed on the server console

        mvc.perform(post("/api/setup/admin").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(setup("wrong-token", PASSWORD)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SETUP_TOKEN"));
        mvc.perform(post("/api/setup/admin").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(setup(token, "weakpassword")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        mvc.perform(post("/api/setup/admin").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(setup(token, PASSWORD)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.username").value(USER));

        mvc.perform(get("/api/setup/status")).andExpect(jsonPath("$.setupRequired").value(false));
        mvc.perform(post("/api/setup/admin").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(setup(token, PASSWORD)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETUP_ALREADY_DONE"));
    }

    @Test
    @Order(2)
    void hrImportsTheWorkbookWithValidation() throws Exception {
        MockHttpSession hr = login();
        byte[] workbook;
        try (InputStream in = getClass().getResourceAsStream("/question-bank.xlsx")) {
            workbook = in.readAllBytes();
        }
        MockMultipartFile file = new MockMultipartFile("file", "Robotrac_Company_Level_Aptitude_Bank_150_40min.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);

        mvc.perform(multipart("/api/admin/questions/import").file(file).with(csrf()).session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(150))
                .andExpect(jsonPath("$.imported").value(149))
                .andExpect(jsonPath("$.flagged").value(1))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.rows[0].code").value("RBT-034"));

        mvc.perform(get("/api/admin/questions/summary").session(hr))
                .andExpect(jsonPath("$.total").value(150))
                .andExpect(jsonPath("$.eligible").value(149))
                .andExpect(jsonPath("$.flagged").value(1));

        // Re-importing the same file changes nothing.
        mvc.perform(multipart("/api/admin/questions/import").file(file).with(csrf()).session(hr))
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.skippedExisting").value(150));

        // A non-Excel upload is refused.
        mvc.perform(multipart("/api/admin/questions/import")
                        .file(new MockMultipartFile("file", "questions.csv", "text/csv", "a,b".getBytes()))
                        .with(csrf()).session(hr))
                .andExpect(status().isBadRequest());

        // HR cannot approve the flagged question until its data problem is fixed.
        String flagged = mvc.perform(get("/api/admin/questions").param("status", "FLAGGED").session(hr))
                .andReturn().getResponse().getContentAsString();
        Integer id = com.jayway.jsonpath.JsonPath.read(flagged, "$.items[0].id");
        mvc.perform(put("/api/admin/questions/" + id + "/review").with(csrf()).session(hr).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"APPROVED\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUESTION_HAS_ISSUES"));
    }

    @Test
    @Order(3)
    void protectedEndpointsRejectAnonymousRequests() throws Exception {
        // The question bank (with answer keys) is HR-only; candidate endpoints need a candidate session.
        mvc.perform(get("/api/admin/questions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/candidate/attempt")).andExpect(status().isUnauthorized());
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/admin/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USER + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private static String setup(String token, String password) {
        return "{\"setupToken\":\"" + token + "\",\"username\":\"" + USER + "\",\"fullName\":\"First Admin\",\"password\":\""
                + password + "\"}";
    }
}

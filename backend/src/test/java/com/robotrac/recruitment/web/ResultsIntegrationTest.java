package com.robotrac.recruitment.web;

import com.robotrac.recruitment.domain.AttemptQuestion;
import com.robotrac.recruitment.support.IntegrationTestBase;
import com.robotrac.recruitment.web.dto.DriveDtos.DriveDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResultsIntegrationTest extends IntegrationTestBase {

    @Test
    void hrSeesScoresDetailsAndCanExportCsv() throws Exception {
        DriveDto drive = liveDrive();
        String email = email();
        // A candidate name that would be a formula in Excel must be neutralised in the export.
        authorizationService.addOne(drive.id(), email, "=HYPERLINK(\"http://x\")", admin(), "127.0.0.1");
        MockHttpSession cand = candidateLogin(email, drive.code());
        startAttempt(cand);
        Candidate c = new Candidate(drive, email, cand);
        List<AttemptQuestion> paper = attemptQuestions.findByAttemptWithQuestion(attemptOf(c).getId());
        int correct = paper.get(0).getOptionOrder().indexOf(paper.get(0).getQuestion().getCorrectOption());
        mvc.perform(put("/api/candidate/attempt/questions/1/response").with(csrf()).session(cand)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"selectedIndex\":" + correct + ",\"markedForReview\":false,\"seq\":1}")).andExpect(status().isOk());
        mvc.perform(post("/api/candidate/attempt/submit").with(csrf()).session(cand)).andExpect(status().isOk());

        MockHttpSession hr = adminLogin();
        String list = mvc.perform(get("/api/admin/results").param("driveId", drive.id().toString()).session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].candidateEmail").value(email))
                .andExpect(jsonPath("$.items[0].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.items[0].correct").value(1))
                .andExpect(jsonPath("$.items[0].unanswered").value(29))
                .andReturn().getResponse().getContentAsString();
        Integer attemptId = read(list, "$.items[0].attemptId");

        mvc.perform(get("/api/admin/results").param("status", "TIMED_OUT").param("driveId", drive.id().toString()).session(hr))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/admin/results").param("q", email.substring(0, 10)).param("sort", "score").session(hr))
                .andExpect(status().isOk());

        mvc.perform(get("/api/admin/results/" + attemptId).session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(30))
                .andExpect(jsonPath("$.questions[0].outcome").value("CORRECT"))
                .andExpect(jsonPath("$.questions[0].correctOption").exists());

        mvc.perform(put("/api/admin/results/" + attemptId + "/review").with(csrf()).session(hr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"REVIEWED_NO_CONCERN\",\"notes\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.reviewStatus").value("REVIEWED_NO_CONCERN"));

        String csv = mvc.perform(get("/api/admin/results/export").param("driveId", drive.id().toString()).session(hr))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("Attempt ID,Candidate email").contains(email).contains("'=HYPERLINK");
        assertThat(csv).doesNotContain(",=HYPERLINK");

        // A question used in an attempt can no longer be edited (scores can never change retroactively).
        Long usedQuestionId = paper.get(0).getQuestion().getId();
        mvc.perform(put("/api/admin/questions/" + usedQuestionId).with(csrf()).session(hr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"x\",\"optionA\":\"1\",\"optionB\":\"2\",\"optionC\":\"3\",\"optionD\":\"4\",\"correctOption\":\"A\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUESTION_IN_USE"));

        mvc.perform(get("/api/admin/dashboard").session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").isNumber())
                .andExpect(jsonPath("$.drives").isArray());
        mvc.perform(get("/api/admin/audit").param("action", "RESULTS_EXPORTED").session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("RESULTS_EXPORTED"));
    }
}

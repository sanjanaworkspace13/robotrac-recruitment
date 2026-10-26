package com.robotrac.recruitment.web;

import com.robotrac.recruitment.domain.AttemptQuestion;
import com.robotrac.recruitment.domain.AttemptStatus;
import com.robotrac.recruitment.domain.Difficulty;
import com.robotrac.recruitment.domain.ExamAttempt;
import com.robotrac.recruitment.domain.ExamResult;
import com.robotrac.recruitment.domain.FinalizedBy;
import com.robotrac.recruitment.domain.Section;
import com.robotrac.recruitment.exception.ApiException;
import com.robotrac.recruitment.security.CandidatePrincipal;
import com.robotrac.recruitment.service.ExamService;
import com.robotrac.recruitment.service.ExpiredAttemptSweeper;
import com.robotrac.recruitment.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExamFlowIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ExamService examService;
    @Autowired
    private ExpiredAttemptSweeper sweeper;

    private static final List<String> FORBIDDEN_KEYS = List.of("correctOption", "correct", "explanation", "difficulty",
            "score", "maxScore", "optionOrder", "code", "questionId", "result", "incorrect", "unanswered");

    private List<AttemptQuestion> paperOf(ExamAttempt a) {
        return attemptQuestions.findByAttemptWithQuestion(a.getId());
    }

    private String save(MockHttpSession s, int number, int index, boolean review, long seq) throws Exception {
        return mvc.perform(put("/api/candidate/attempt/questions/" + number + "/response").with(csrf()).session(s)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedIndex\":" + index + ",\"markedForReview\":" + review + ",\"seq\":" + seq + "}"))
                .andReturn().getResponse().getContentAsString();
    }

    /** Display index (0-3) at which the correct option of question {@code aq} is shown. */
    private static int correctIndex(AttemptQuestion aq) {
        return aq.getOptionOrder().indexOf(aq.getQuestion().getCorrectOption());
    }

    @Test
    void startCreatesOnePaperWithConfiguredDistribution() throws Exception {
        Candidate c = liveCandidate();
        String body = startAttempt(c.session());
        assertThat((Integer) read(body, "$.totalQuestions")).isEqualTo(30);
        assertThat((String) read(body, "$.status")).isEqualTo("IN_PROGRESS");

        List<AttemptQuestion> paper = paperOf(attemptOf(c));
        assertThat(paper).hasSize(30);
        Map<Difficulty, Integer> byDifficulty = new EnumMap<>(Difficulty.class);
        Map<Section, Integer> bySection = new EnumMap<>(Section.class);
        for (AttemptQuestion aq : paper) {
            byDifficulty.merge(aq.getQuestion().getDifficulty(), 1, Integer::sum);
            bySection.merge(aq.getQuestion().getSection(), 1, Integer::sum);
            assertThat(aq.getQuestion().getCode()).isNotEqualTo("RBT-034"); // flagged question never used
        }
        assertThat(byDifficulty).containsEntry(Difficulty.EASY, 5).containsEntry(Difficulty.MODERATE, 15)
                .containsEntry(Difficulty.HARD, 10);
        assertThat(bySection).containsEntry(Section.QUANTITATIVE, 10).containsEntry(Section.LOGICAL, 10)
                .containsEntry(Section.ANALYTICAL, 10);
        assertThat(paper.stream().map(AttemptQuestion::getQuestion).distinct().count()).isEqualTo(30);
    }

    @Test
    void candidateApiNeverExposesAnswerKeysOrScoring() throws Exception {
        Candidate c = liveCandidate();
        String start = startAttempt(c.session());
        String state = mvc.perform(get("/api/candidate/attempt").session(c.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (String body : List.of(start, state)) {
            assertThat(jsonKeys(body)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
            assertThat(body).doesNotContain("RBT-"); // internal question ids are not sent either
        }
        // Options are shown as plain texts in the stored (shuffled) order.
        List<AttemptQuestion> paper = paperOf(attemptOf(c));
        List<String> shown = read(state, "$.questions[0].options");
        AttemptQuestion first = paper.get(0);
        for (int i = 0; i < 4; i++) {
            assertThat(shown.get(i)).isEqualTo(first.getQuestion().optionText(first.getOptionOrder().charAt(i)));
        }
    }

    @Test
    void refreshAndRepeatedStartKeepTheSameAttemptAndOrder() throws Exception {
        Candidate c = liveCandidate();
        String first = startAttempt(c.session());
        String again = startAttempt(c.session());                 // repeated request
        String refreshed = mvc.perform(get("/api/candidate/attempt").session(c.session()))
                .andReturn().getResponse().getContentAsString();  // page refresh
        List<String> t1 = read(first, "$.questions[*].text");
        assertThat((List<String>) read(again, "$.questions[*].text")).isEqualTo(t1);
        assertThat((List<String>) read(refreshed, "$.questions[*].text")).isEqualTo(t1);
        assertThat((List<List<String>>) read(refreshed, "$.questions[*].options"))
                .isEqualTo(read(first, "$.questions[*].options"));
        assertThat(attempts.findAll().stream().filter(a -> a.getDrive().getId().equals(c.drive().id())).count()).isEqualTo(1);

        // Logging out and in again resumes the same attempt.
        mvc.perform(post("/api/auth/logout").with(csrf()).session(c.session())).andExpect(status().isNoContent());
        MockHttpSession again2 = candidateLogin(c.email(), c.drive().code());
        String resumed = mvc.perform(get("/api/candidate/attempt").session(again2)).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) read(resumed, "$.questions[*].text")).isEqualTo(t1);
    }

    @Test
    void answersPersistAndAreRestored() throws Exception {
        Candidate c = liveCandidate();
        startAttempt(c.session());
        String saved = save(c.session(), 1, 2, false, 1);
        assertThat((Integer) read(saved, "$.selectedIndex")).isEqualTo(2);
        save(c.session(), 2, 0, true, 2); // answered + marked for review
        mvc.perform(post("/api/candidate/attempt/questions/3/review").with(csrf()).session(c.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"markedForReview\":true,\"seq\":3}")).andExpect(status().isOk());
        mvc.perform(post("/api/candidate/attempt/questions/4/visit").with(csrf()).session(c.session())).andExpect(status().isOk());

        String state = mvc.perform(get("/api/candidate/attempt").session(c.session())).andReturn().getResponse().getContentAsString();
        assertThat((Integer) read(state, "$.questions[0].selectedIndex")).isEqualTo(2);
        assertThat((Boolean) read(state, "$.questions[1].markedForReview")).isTrue();
        assertThat((Integer) read(state, "$.questions[1].selectedIndex")).isEqualTo(0);
        assertThat((Boolean) read(state, "$.questions[2].markedForReview")).isTrue();
        assertThat((Object) read(state, "$.questions[2].selectedIndex")).isNull();
        assertThat((Boolean) read(state, "$.questions[3].visited")).isTrue();
        assertThat((Boolean) read(state, "$.questions[4].visited")).isFalse();

        // Clear response removes the answer but keeps the question visited.
        mvc.perform(post("/api/candidate/attempt/questions/1/clear").with(csrf()).session(c.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seq\":10}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.selectedIndex").doesNotExist());
    }

    @Test
    void olderUpdatesCannotOverwriteNewerOnes() throws Exception {
        Candidate c = liveCandidate();
        startAttempt(c.session());
        save(c.session(), 1, 3, false, 5);
        mvc.perform(put("/api/candidate/attempt/questions/1/response").with(csrf()).session(c.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"selectedIndex\":1,\"markedForReview\":false,\"seq\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_UPDATE"))
                .andExpect(jsonPath("$.details.selectedIndex").value(3));
    }

    @Test
    void scoringIsDoneOnTheServer() throws Exception {
        Candidate c = liveCandidate();
        startAttempt(c.session());
        List<AttemptQuestion> paper = paperOf(attemptOf(c));
        save(c.session(), 1, correctIndex(paper.get(0)), false, 1);                 // correct
        save(c.session(), 2, (correctIndex(paper.get(1)) + 1) % 4, false, 2);       // incorrect
        save(c.session(), 3, correctIndex(paper.get(2)), true, 3);                  // correct + marked for review

        String body = mvc.perform(post("/api/candidate/attempt/submit").with(csrf()).session(c.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"MANUAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(ExamService.NEUTRAL_MESSAGE))
                .andReturn().getResponse().getContentAsString();
        assertThat(jsonKeys(body)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);

        ExamAttempt a = attemptOf(c);
        assertThat(a.getStatus()).isEqualTo(AttemptStatus.SUBMITTED);
        assertThat(a.getFinalizedBy()).isEqualTo(FinalizedBy.CANDIDATE);
        ExamResult r = results.findByAttemptId(a.getId()).orElseThrow();
        assertThat(r.getCorrectCount()).isEqualTo(2);
        assertThat(r.getIncorrectCount()).isEqualTo(1);
        assertThat(r.getUnansweredCount()).isEqualTo(27);
        assertThat(r.getScore()).isEqualByComparingTo("2.00");
        assertThat(r.getMaxScore()).isEqualByComparingTo("30.00");
    }

    @Test
    void submissionIsIdempotentAndASecondAttemptIsImpossible() throws Exception {
        Candidate c = liveCandidate();
        startAttempt(c.session());
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/candidate/attempt/submit").with(csrf()).session(c.session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(ExamService.NEUTRAL_MESSAGE));
        }
        ExamAttempt a = attemptOf(c);
        assertThat(results.findAll().stream().filter(r -> r.getAttempt().getId().equals(a.getId())).count()).isEqualTo(1);

        mvc.perform(post("/api/candidate/attempt/start").with(csrf()).session(c.session()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTEMPT_CLOSED"));
        mvc.perform(put("/api/candidate/attempt/questions/1/response").with(csrf()).session(c.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"selectedIndex\":1,\"markedForReview\":false,\"seq\":99}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTEMPT_CLOSED"));
        mvc.perform(get("/api/candidate/attempt").session(c.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.questions").doesNotExist());
        // Logging in again after completion is refused.
        candidateLoginRaw(c.email(), c.drive().code(), DRIVE_PASSWORD)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTEMPT_CLOSED"));
        assertThat(attempts.findAll().stream().filter(x -> x.getDrive().getId().equals(c.drive().id())).count()).isEqualTo(1);
    }

    @Test
    void parallelStartRequestsCreateExactlyOneAttempt() throws Exception {
        Candidate c = liveCandidate();
        CandidatePrincipal p = new CandidatePrincipal(attemptlessCandidateId(c), c.drive().id(), c.email(), c.drive().code());
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<String> task = () -> {
                go.await();
                try {
                    return examService.start(p, "127.0.0.1").questions().get(0).text();
                } catch (ApiException e) {
                    return "ERR:" + e.getCode();
                } catch (RuntimeException e) {
                    return "ERR:" + e.getClass().getSimpleName();
                }
            };
            futures.add(pool.submit(task));
        }
        go.countDown();
        List<String> firstTexts = new ArrayList<>();
        for (Future<String> f : futures) {
            firstTexts.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(attempts.findAll().stream().filter(a -> a.getDrive().getId().equals(c.drive().id())).count()).isEqualTo(1);
        // Every successful caller got the same attempt (same first question).
        assertThat(firstTexts.stream().filter(t -> !t.startsWith("ERR:")).distinct().count()).isEqualTo(1);
    }

    private Long attemptlessCandidateId(Candidate c) {
        return authorizationService.list(c.drive().id(), c.email(), 0, 1).items().get(0).candidateId();
    }

    @Test
    void timerContinuesAfterRefresh() throws Exception {
        Candidate c = liveCandidate();
        String start = startAttempt(c.session());
        long initial = ((Number) read(start, "$.remainingSeconds")).longValue();
        assertThat(initial).isBetween(39 * 60L, 40 * 60L);
        clock.advance(Duration.ofMinutes(5));
        String later = mvc.perform(get("/api/candidate/attempt").session(c.session())).andReturn().getResponse().getContentAsString();
        assertThat(((Number) read(later, "$.remainingSeconds")).longValue()).isBetween(initial - 301, initial - 299);
        assertThat((String) read(later, "$.deadlineAt")).isEqualTo(read(start, "$.deadlineAt"));
    }

    @Test
    void backendRejectsAnswersAfterTheDeadline() throws Exception {
        Candidate c = liveCandidate();
        startAttempt(c.session());
        save(c.session(), 1, 0, false, 1);
        clock.advance(Duration.ofMinutes(41));
        mvc.perform(put("/api/candidate/attempt/questions/2/response").with(csrf()).session(c.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"selectedIndex\":1,\"markedForReview\":false,\"seq\":2}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTEMPT_CLOSED"));
        ExamAttempt a = attemptOf(c);
        assertThat(a.getStatus()).isEqualTo(AttemptStatus.TIMED_OUT);
        ExamResult r = results.findByAttemptId(a.getId()).orElseThrow();
        assertThat(r.getCorrectCount() + r.getIncorrectCount()).isEqualTo(1); // only the pre-deadline answer counts
    }

    @Test
    void serverFinalisesExpiredAttemptsEvenIfTheBrowserIsGone() throws Exception {
        Candidate c = liveCandidate();
        startAttempt(c.session());
        clock.advance(Duration.ofMinutes(39));
        sweeper.sweep();
        assertThat(attemptOf(c).getStatus()).isEqualTo(AttemptStatus.IN_PROGRESS);
        clock.advance(Duration.ofMinutes(2));
        assertThat(sweeper.sweep()).isGreaterThanOrEqualTo(1);
        ExamAttempt a = attemptOf(c);
        assertThat(a.getStatus()).isEqualTo(AttemptStatus.TIMED_OUT);
        assertThat(a.getFinalizedBy()).isEqualTo(FinalizedBy.SERVER_DEADLINE);
        assertThat(results.findByAttemptId(a.getId())).isPresent();
        // A late manual submit is harmless and still neutral.
        mvc.perform(post("/api/candidate/attempt/submit").with(csrf()).session(c.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value(ExamService.NEUTRAL_MESSAGE));
    }

    @Test
    void clientTimerSubmissionAtDeadlineIsRecordedAsTimedOut() throws Exception {
        Candidate c = liveCandidate();
        startAttempt(c.session());
        clock.advance(Duration.ofMinutes(40));
        mvc.perform(post("/api/candidate/attempt/submit").with(csrf()).session(c.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"TIMER\"}")).andExpect(status().isOk());
        ExamAttempt a = attemptOf(c);
        assertThat(a.getStatus()).isEqualTo(AttemptStatus.TIMED_OUT);
        assertThat(a.getFinalizedBy()).isEqualTo(FinalizedBy.CLIENT_TIMER);
    }

    @Test
    void closingADriveLetsAttemptsInProgressFinish() throws Exception {
        Candidate c = liveCandidate();
        String newcomer = email();
        authorize(c.drive().id(), newcomer);
        startAttempt(c.session());
        driveService.close(c.drive().id(), admin(), "127.0.0.1");
        assertThat(save(c.session(), 1, 1, false, 1)).contains("\"selectedIndex\":1");
        // ...but an authorised candidate who had not started can no longer begin.
        candidateLoginRaw(newcomer, c.drive().code(), DRIVE_PASSWORD)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRIVE_NOT_ACTIVE"));
    }

    @Test
    void candidateCannotSeeResultsEvenAfterSubmission() throws Exception {
        Candidate c = liveCandidate();
        startAttempt(c.session());
        mvc.perform(post("/api/candidate/attempt/submit").with(csrf()).session(c.session())).andExpect(status().isOk());
        String state = mvc.perform(get("/api/candidate/attempt").session(c.session())).andReturn().getResponse().getContentAsString();
        assertThat(jsonKeys(state)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        assertThat(state).doesNotContain("TIMED_OUT").doesNotContain("SUBMITTED");
        mvc.perform(get("/api/admin/results").session(c.session())).andExpect(status().isForbidden());
    }
}

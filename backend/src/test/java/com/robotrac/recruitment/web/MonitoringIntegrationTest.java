package com.robotrac.recruitment.web;

import com.robotrac.recruitment.domain.EventSource;
import com.robotrac.recruitment.domain.ExamAttempt;
import com.robotrac.recruitment.domain.MonitoringEvent;
import com.robotrac.recruitment.domain.MonitoringEventType;
import com.robotrac.recruitment.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MonitoringIntegrationTest extends IntegrationTestBase {

    private ResultActions event(MockHttpSession s, String type, String id, String extra) throws Exception {
        return mvc.perform(post("/api/candidate/attempt/events").with(csrf()).session(s)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"" + type + "\",\"clientEventId\":\"" + id + "\",\"clientTime\":\""
                        + Instant.now() + "\"" + extra + "}"));
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private List<MonitoringEvent> clientEvents(ExamAttempt a) {
        return events.findByAttemptIdOrderByServerTimeAscIdAsc(a.getId()).stream()
                .filter(e -> e.getSource() == EventSource.CLIENT).toList();
    }

    @Test
    void tabSwitchIsPersistedWithServerTimestamp() throws Exception {
        Candidate c = liveCandidate();
        startAttempt(c.session());
        event(c.session(), "TAB_HIDDEN", id(), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded").value(true))
                .andExpect(jsonPath("$.awayCount").value(1))
                .andExpect(jsonPath("$.warningThreshold").value(3));
        clock.advance(Duration.ofSeconds(5));
        event(c.session(), "TAB_VISIBLE", id(), "").andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(5));
        event(c.session(), "WINDOW_BLUR", id(), "").andExpect(jsonPath("$.awayCount").value(2));

        List<MonitoringEvent> stored = clientEvents(attemptOf(c));
        assertThat(stored).extracting(MonitoringEvent::getEventType)
                .containsExactly(MonitoringEventType.TAB_HIDDEN, MonitoringEventType.TAB_VISIBLE, MonitoringEventType.WINDOW_BLUR);
        assertThat(stored.get(0).getServerTime()).isNotNull();
    }

    @Test
    void retriesAndDoubleFiredBrowserEventsAreNotDoubleCounted() throws Exception {
        Candidate c = liveCandidate();
        startAttempt(c.session());
        String sameId = id();
        event(c.session(), "TAB_HIDDEN", sameId, "").andExpect(jsonPath("$.recorded").value(true));
        event(c.session(), "TAB_HIDDEN", sameId, "")                 // network retry, same id
                .andExpect(jsonPath("$.duplicate").value(true)).andExpect(jsonPath("$.awayCount").value(1));
        event(c.session(), "TAB_HIDDEN", id(), "")                   // same transition fired twice within 1 s
                .andExpect(jsonPath("$.duplicate").value(true)).andExpect(jsonPath("$.awayCount").value(1));
        assertThat(clientEvents(attemptOf(c))).hasSize(1);
    }

    @Test
    void browserCannotDictateCountsOrServerOnlyEvents() throws Exception {
        Candidate c = liveCandidate();
        startAttempt(c.session());
        // An extra "count" field is simply ignored; the server counts stored rows.
        event(c.session(), "WINDOW_BLUR", id(), ",\"count\":999,\"awayCount\":999")
                .andExpect(status().isOk()).andExpect(jsonPath("$.awayCount").value(1));
        event(c.session(), "LOGIN", id(), "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EVENT_TYPE"));
        event(c.session(), "HACKED", id(), "").andExpect(status().isBadRequest());
        event(c.session(), "TAB_HIDDEN", "bad id!", "").andExpect(status().isBadRequest());
    }

    @Test
    void eventsAreRejectedWithoutAnActiveAttempt() throws Exception {
        Candidate c = liveCandidate();
        event(c.session(), "TAB_HIDDEN", id(), "").andExpect(status().isNotFound());
        startAttempt(c.session());
        mvc.perform(post("/api/candidate/attempt/submit").with(csrf()).session(c.session())).andExpect(status().isOk());
        event(c.session(), "TAB_HIDDEN", id(), "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTEMPT_CLOSED"));
    }

    @Test
    void monitoringHistoryIsVisibleToHrOnly() throws Exception {
        Candidate c = liveCandidate();
        startAttempt(c.session());
        event(c.session(), "TAB_HIDDEN", id(), "").andExpect(status().isOk());
        Long attemptId = attemptOf(c).getId();

        mvc.perform(get("/api/admin/results/" + attemptId + "/events").session(c.session()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/results/" + attemptId + "/events"))
                .andExpect(status().isUnauthorized());

        MockHttpSession hr = adminLogin();
        mvc.perform(get("/api/admin/results/" + attemptId + "/events").session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='TAB_HIDDEN')].source").value("CLIENT"))
                .andExpect(jsonPath("$[?(@.type=='ATTEMPT_STARTED')].source").value("SERVER"));
        mvc.perform(get("/api/admin/results/" + attemptId).session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventCounts.TAB_HIDDEN").value(1))
                .andExpect(jsonPath("$.summary.awayCount").value(1));
    }

    @Test
    void reloginDuringAttemptIsRecordedAsServerEvent() throws Exception {
        Candidate c = liveCandidate();
        startAttempt(c.session());
        candidateLogin(c.email(), c.drive().code());
        assertThat(events.findByAttemptIdOrderByServerTimeAscIdAsc(attemptOf(c).getId()))
                .extracting(MonitoringEvent::getEventType).contains(MonitoringEventType.LOGIN);
    }
}

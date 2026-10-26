package com.robotrac.recruitment.domain;

/**
 * Exam-integrity events. CLIENT events are detected by the browser and reported by the exam page;
 * SERVER events are recorded by the backend itself. All are timestamped by the server.
 */
public enum MonitoringEventType {
    TAB_HIDDEN(true, true, "Exam tab hidden (switched tab or minimised)"),
    TAB_VISIBLE(true, false, "Exam tab visible again"),
    WINDOW_BLUR(true, true, "Exam window lost focus"),
    WINDOW_FOCUS(true, false, "Exam window regained focus"),
    FULLSCREEN_EXIT(true, false, "Left full-screen mode"),
    PAGE_LOADED(true, false, "Exam page loaded or reloaded"),
    PAGE_UNLOAD(true, false, "Exam page closed, reloaded or navigated away"),
    LOGIN(false, false, "Candidate logged in (server-verified)"),
    ATTEMPT_STARTED(false, false, "Attempt started (server-verified)");

    private final boolean clientReportable;
    private final boolean away;
    private final String description;

    MonitoringEventType(boolean clientReportable, boolean away, String description) {
        this.clientReportable = clientReportable;
        this.away = away;
        this.description = description;
    }

    /** May the exam page report this type? Server-only types are rejected from clients. */
    public boolean isClientReportable() {
        return clientReportable;
    }

    /** Counts towards the "left the exam" total shown to HR and the candidate warning. */
    public boolean isAway() {
        return away;
    }

    public String getDescription() {
        return description;
    }
}

package com.robotrac.recruitment.domain;

/** Who or what closed an attempt. */
public enum FinalizedBy {
    /** Candidate pressed Final Submit. */
    CANDIDATE,
    /** The candidate's browser timer reached zero and auto-submitted. */
    CLIENT_TIMER,
    /** The server found the deadline had passed (scheduler or a later request). */
    SERVER_DEADLINE
}

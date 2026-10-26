package com.robotrac.recruitment.domain;

/**
 * Phase computed from the stored status and the server clock.
 * An ACTIVE drive is SCHEDULED before its start time, LIVE inside its window and ENDED after its end time.
 */
public enum DrivePhase {
    DRAFT, SCHEDULED, LIVE, ENDED, CLOSED
}

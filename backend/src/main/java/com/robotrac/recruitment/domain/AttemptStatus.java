package com.robotrac.recruitment.domain;

public enum AttemptStatus {
    IN_PROGRESS, SUBMITTED, TIMED_OUT;

    public boolean isFinal() {
        return this != IN_PROGRESS;
    }
}

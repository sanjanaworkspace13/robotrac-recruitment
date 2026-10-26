-- =====================================================================
-- Robotrac Recruitment Portal - initial schema (MySQL 8, InnoDB, utf8mb4)
-- All timestamps are stored in UTC as DATETIME(6).
-- Nothing in this schema is ever hard-deleted by the application:
-- drives are CLOSED, authorizations are REVOKED, questions are deactivated.
-- =====================================================================

CREATE TABLE admin_user (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    username        VARCHAR(60)  NOT NULL,
    full_name       VARCHAR(120) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    enabled         BOOLEAN      NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    last_login_at   DATETIME(6)  NULL,
    CONSTRAINT pk_admin_user PRIMARY KEY (id),
    CONSTRAINT uk_admin_user_username UNIQUE (username)
);

CREATE TABLE candidate (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    email       VARCHAR(254) NOT NULL,
    full_name   VARCHAR(120) NULL,
    created_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_candidate PRIMARY KEY (id),
    CONSTRAINT uk_candidate_email UNIQUE (email)
);

CREATE TABLE recruitment_drive (
    id                          BIGINT        NOT NULL AUTO_INCREMENT,
    name                        VARCHAR(150)  NOT NULL,
    code                        VARCHAR(20)   NOT NULL,
    description                 VARCHAR(1000) NULL,
    start_at                    DATETIME(6)   NOT NULL,
    end_at                      DATETIME(6)   NOT NULL,
    status                      VARCHAR(20)   NOT NULL,
    duration_minutes            INT           NOT NULL,
    easy_count                  INT           NOT NULL,
    moderate_count              INT           NOT NULL,
    hard_count                  INT           NOT NULL,
    section_balanced            BOOLEAN       NOT NULL,
    shuffle_options             BOOLEAN       NOT NULL,
    marks_per_correct           DECIMAL(6,2)  NOT NULL,
    negative_marking_enabled    BOOLEAN       NOT NULL,
    negative_marks              DECIMAL(6,2)  NOT NULL,
    away_warning_threshold      INT           NOT NULL,
    password_hash               VARCHAR(100)  NULL,
    created_by                  BIGINT        NOT NULL,
    created_at                  DATETIME(6)   NOT NULL,
    updated_at                  DATETIME(6)   NOT NULL,
    activated_at                DATETIME(6)   NULL,
    closed_at                   DATETIME(6)   NULL,
    version                     BIGINT        NOT NULL,
    CONSTRAINT pk_recruitment_drive PRIMARY KEY (id),
    CONSTRAINT uk_recruitment_drive_code UNIQUE (code),
    CONSTRAINT fk_drive_created_by FOREIGN KEY (created_by) REFERENCES admin_user (id)
);
CREATE INDEX idx_drive_status ON recruitment_drive (status);

CREATE TABLE drive_authorization (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    drive_id      BIGINT      NOT NULL,
    candidate_id  BIGINT      NOT NULL,
    status        VARCHAR(20) NOT NULL,
    added_by      BIGINT      NULL,
    created_at    DATETIME(6) NOT NULL,
    revoked_at    DATETIME(6) NULL,
    CONSTRAINT pk_drive_authorization PRIMARY KEY (id),
    CONSTRAINT uk_drive_authorization UNIQUE (drive_id, candidate_id),
    CONSTRAINT fk_auth_drive FOREIGN KEY (drive_id) REFERENCES recruitment_drive (id),
    CONSTRAINT fk_auth_candidate FOREIGN KEY (candidate_id) REFERENCES candidate (id),
    CONSTRAINT fk_auth_added_by FOREIGN KEY (added_by) REFERENCES admin_user (id)
);
CREATE INDEX idx_auth_candidate ON drive_authorization (candidate_id);

CREATE TABLE question (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    code              VARCHAR(40)   NOT NULL,
    section           VARCHAR(20)   NOT NULL,
    difficulty        VARCHAR(20)   NOT NULL,
    question_text     VARCHAR(2000) NOT NULL,
    option_a          VARCHAR(500)  NOT NULL,
    option_b          VARCHAR(500)  NOT NULL,
    option_c          VARCHAR(500)  NOT NULL,
    option_d          VARCHAR(500)  NOT NULL,
    correct_option    CHAR(1)       NULL,
    explanation       VARCHAR(2000) NULL,
    source_marks      DECIMAL(6,2)  NULL,
    review_status     VARCHAR(20)   NOT NULL,
    validation_notes  VARCHAR(1000) NULL,
    active            BOOLEAN       NOT NULL,
    source_row        INT           NULL,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    CONSTRAINT pk_question PRIMARY KEY (id),
    CONSTRAINT uk_question_code UNIQUE (code)
);
CREATE INDEX idx_question_pool ON question (active, review_status, section, difficulty);

CREATE TABLE exam_attempt (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    drive_id          BIGINT        NOT NULL,
    candidate_id      BIGINT        NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    started_at        DATETIME(6)   NOT NULL,
    deadline_at       DATETIME(6)   NOT NULL,
    submitted_at      DATETIME(6)   NULL,
    finalized_by      VARCHAR(30)   NULL,
    review_status     VARCHAR(30)   NOT NULL,
    review_notes      VARCHAR(2000) NULL,
    CONSTRAINT pk_exam_attempt PRIMARY KEY (id),
    -- The database itself guarantees ONE attempt per candidate per drive.
    CONSTRAINT uk_attempt_drive_candidate UNIQUE (drive_id, candidate_id),
    CONSTRAINT fk_attempt_drive FOREIGN KEY (drive_id) REFERENCES recruitment_drive (id),
    CONSTRAINT fk_attempt_candidate FOREIGN KEY (candidate_id) REFERENCES candidate (id)
);
CREATE INDEX idx_attempt_status_deadline ON exam_attempt (status, deadline_at);
CREATE INDEX idx_attempt_candidate ON exam_attempt (candidate_id);

-- One row per question assigned to an attempt. The candidate's response lives on the
-- same row, which makes duplicate answer rows impossible.
CREATE TABLE attempt_question (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    attempt_id         BIGINT      NOT NULL,
    question_id        BIGINT      NOT NULL,
    display_order      INT         NOT NULL,
    option_order       CHAR(4)     NOT NULL,
    selected_option    CHAR(1)     NULL,
    marked_for_review  BOOLEAN     NOT NULL,
    visited            BOOLEAN     NOT NULL,
    last_seq           BIGINT      NOT NULL,
    answered_at        DATETIME(6) NULL,
    updated_at         DATETIME(6) NULL,
    CONSTRAINT pk_attempt_question PRIMARY KEY (id),
    CONSTRAINT uk_attempt_question_order UNIQUE (attempt_id, display_order),
    CONSTRAINT uk_attempt_question_question UNIQUE (attempt_id, question_id),
    CONSTRAINT fk_aq_attempt FOREIGN KEY (attempt_id) REFERENCES exam_attempt (id),
    CONSTRAINT fk_aq_question FOREIGN KEY (question_id) REFERENCES question (id)
);
CREATE INDEX idx_aq_question ON attempt_question (question_id);

CREATE TABLE monitoring_event (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    attempt_id        BIGINT       NOT NULL,
    event_type        VARCHAR(30)  NOT NULL,
    event_source      VARCHAR(10)  NOT NULL,
    server_time       DATETIME(6)  NOT NULL,
    client_time       DATETIME(6)  NULL,
    client_event_id   VARCHAR(64)  NULL,
    detail            VARCHAR(255) NULL,
    CONSTRAINT pk_monitoring_event PRIMARY KEY (id),
    CONSTRAINT uk_monitoring_client_event UNIQUE (attempt_id, client_event_id),
    CONSTRAINT fk_event_attempt FOREIGN KEY (attempt_id) REFERENCES exam_attempt (id)
);
CREATE INDEX idx_event_attempt_time ON monitoring_event (attempt_id, server_time);

CREATE TABLE exam_result (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    attempt_id        BIGINT       NOT NULL,
    correct_count     INT          NOT NULL,
    incorrect_count   INT          NOT NULL,
    unanswered_count  INT          NOT NULL,
    score             DECIMAL(8,2) NOT NULL,
    max_score         DECIMAL(8,2) NOT NULL,
    computed_at       DATETIME(6)  NOT NULL,
    CONSTRAINT pk_exam_result PRIMARY KEY (id),
    -- Scoring can never be stored twice for the same attempt.
    CONSTRAINT uk_exam_result_attempt UNIQUE (attempt_id),
    CONSTRAINT fk_result_attempt FOREIGN KEY (attempt_id) REFERENCES exam_attempt (id)
);

CREATE TABLE audit_log (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    created_at    DATETIME(6)   NOT NULL,
    actor_type    VARCHAR(20)   NOT NULL,
    actor_id      BIGINT        NULL,
    actor_name    VARCHAR(254)  NULL,
    action_code   VARCHAR(60)   NOT NULL,
    entity_type   VARCHAR(40)   NULL,
    entity_id     VARCHAR(60)   NULL,
    details       VARCHAR(4000) NULL,
    ip_address    VARCHAR(64)   NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (id)
);
CREATE INDEX idx_audit_created ON audit_log (created_at);
CREATE INDEX idx_audit_action ON audit_log (action_code);

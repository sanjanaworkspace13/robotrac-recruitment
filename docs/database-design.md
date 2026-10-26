# Database design

MySQL 8 (InnoDB, utf8mb4). The schema is created and versioned by **Flyway** from
`backend/src/main/resources/db/migration` (`V1__initial_schema.sql`); `database/schema.sql` is a read-only copy.
Hibernate never changes the schema (`ddl-auto=none`).

## Entity relationships

```
admin_user 1───* recruitment_drive (created_by)
admin_user 1───* drive_authorization (added_by)

recruitment_drive 1───* drive_authorization *───1 candidate
recruitment_drive 1───* exam_attempt        *───1 candidate        UNIQUE(drive_id, candidate_id)
exam_attempt      1───* attempt_question    *───1 question         UNIQUE(attempt_id, display_order), UNIQUE(attempt_id, question_id)
exam_attempt      1───* monitoring_event                           UNIQUE(attempt_id, client_event_id)
exam_attempt      1───1 exam_result                                UNIQUE(attempt_id)
audit_log         (no foreign keys: append-only history that survives anything)
```

## Tables

| Table | Purpose | Key columns / constraints |
|---|---|---|
| `admin_user` | HR accounts | `username` UNIQUE; `password_hash` (BCrypt via Spring's delegating encoder, e.g. `{bcrypt}$2a$10$...`) |
| `candidate` | A person, identified by normalised email | `email` UNIQUE (trimmed, lower-case) |
| `recruitment_drive` | One test event | `code` UNIQUE (upper-case); `status` DRAFT/ACTIVE/CLOSED; schedule `start_at`/`end_at`; paper config (`easy_count`, `moderate_count`, `hard_count`, `section_balanced`, `shuffle_options`, `duration_minutes`); marking (`marks_per_correct`, `negative_marking_enabled`, `negative_marks`); `away_warning_threshold`; `password_hash` (BCrypt of the shared drive password, nullable until set); `version` for optimistic locking |
| `drive_authorization` | Email X may sit drive Y | UNIQUE(`drive_id`,`candidate_id`); `status` AUTHORIZED/REVOKED (never deleted) |
| `question` | Question bank | `code` UNIQUE (workbook "Question ID"); `section`; `difficulty`; text and 4 options; `correct_option` A-D (NULL when the workbook had no valid key); `review_status` NEEDS_REVIEW/APPROVED/FLAGGED; `validation_notes`; `active`; `source_row` |
| `exam_attempt` | The single attempt | **UNIQUE(`drive_id`,`candidate_id`)**; `status` IN_PROGRESS/SUBMITTED/TIMED_OUT; server `started_at`, `deadline_at`, `submitted_at`; `finalized_by`; HR `review_status`, `review_notes` |
| `attempt_question` | The candidate's fixed paper **and** their response | `display_order` (1..30), `option_order` (permutation of "ABCD"), `selected_option` (original letter or NULL), `marked_for_review`, `visited`, `last_seq` (ordering guard), timestamps |
| `monitoring_event` | Exam-integrity events | `event_type`, `event_source` CLIENT/SERVER, authoritative `server_time`, informational `client_time`, `client_event_id` (dedupe), `detail` |
| `exam_result` | Server-computed score | UNIQUE(`attempt_id`); correct/incorrect/unanswered counts; `score`, `max_score` |
| `audit_log` | Sensitive actions | actor type/id/name, `action_code`, entity, details (no secrets), IP, time |

Indexes: drive status; authorisation by candidate; question pool `(active, review_status, section, difficulty)`;
attempts by `(status, deadline_at)` for the timeout sweeper and by candidate; attempt questions by question;
events by `(attempt_id, server_time)`; audit by time and action. MySQL also indexes every foreign key.

## Design decisions

* **Response stored on `attempt_question`.** One row per (attempt, question) holds both the assignment and the
  answer, so duplicate answer rows are impossible. `last_seq` rejects out-of-order updates.
* **Answer key isolation.** The key lives only in `question.correct_option`. The browser sees option texts in the
  stored `option_order` and answers with display indexes; the server maps index → original letter.
* **Timestamps.** All `DATETIME(6)` values are UTC (JVM default time zone set to UTC, Hibernate
  `jdbc.time_zone=UTC`, JDBC `connectionTimeZone=UTC`). The server clock is authoritative for drive windows and
  deadlines; browsers only display local time.
* **No destructive deletes.** Drives are CLOSED, authorisations REVOKED, questions deactivated; foreign keys use the
  default RESTRICT so results and events can never be orphaned or silently removed. A revocation is refused once
  an attempt exists. Questions used in attempts cannot be edited.
* **Transactions and locking.** Starting an attempt locks the authorisation row; every answer save, submit, timeout
  and monitoring event locks the attempt row (`SELECT … FOR UPDATE`) and re-reads it fresh, so a manual submit,
  the browser timer and the server sweeper can never score twice or accept an answer after finalisation.

## Paper allocation policy

For each candidate at "Start Examination":

1. Eligible pool = active, not FLAGGED, with a valid key (NEEDS_REVIEW questions are eligible; HR is warned).
2. Difficulty counts are exact (default 5 easy, 15 moderate, 10 hard).
3. With section balancing (default) the paper is split evenly over the 3 sections (30 → 10/10/10; remainders go
   to random sections), and each difficulty is spread as evenly as availability allows (e.g. easy 2/2/1,
   moderate 5/5/5, hard 3/3/4), with random tie-breaks per candidate. A max-flow check guarantees a valid split is
   found whenever one exists; otherwise HR gets a precise error at activation time.
4. Questions are drawn uniformly at random within each (section, difficulty) cell; the paper is presented section by
   section, shuffled within each section; options are shuffled per question when enabled.
5. Everything is stored, so refresh/re-login shows exactly the same paper.

With the supplied workbook (after flagging RBT-034): Quantitative 14/25/11, Logical 13/24/12, Analytical 14/24/12
(easy/moderate/hard) — ample for 5/15/10 with 10 per section.

## Question import (workbook)

Columns are matched by header name in sheet "Recruitment Question Bank" (or the first sheet):
`Question ID, Section, Difficulty, Question, Option A–D, Correct Option` (required) and
`Correct Answer, Explanation, Marks, HR Review Status` (optional).

| Outcome | When |
|---|---|
| REJECTED (reported, not stored) | missing ID/text/any option, unknown section or difficulty, duplicate ID in the file, over-long text |
| FLAGGED (stored, excluded from papers) | missing or invalid key, "Correct Answer" text not matching the keyed option, identical options, duplicate question wording |
| IMPORTED | otherwise; the workbook's HR Review Status is kept ("Approved" → APPROVED, anything else → NEEDS_REVIEW) |
| SKIPPED_EXISTING | a question with that ID is already in the bank (left unchanged) |

Nothing is invented: a missing key stays NULL until HR fixes it. Validation messages never echo answer text.
Findings on the supplied file: 150 rows, 0 rejected, 1 flagged (**RBT-034**: Option A and Option C are both "NPUQS").

## Monitoring policy

Events counted as "left the exam window": `TAB_HIDDEN`, `WINDOW_BLUR`. Return events (`TAB_VISIBLE`,
`WINDOW_FOCUS`), `FULLSCREEN_EXIT`, `PAGE_LOADED` (reloads), `PAGE_UNLOAD` are recorded for context; `LOGIN` (re-login
during an attempt) and `ATTEMPT_STARTED` are recorded by the server itself. Attempts at or above the drive's
`away_warning_threshold` are highlighted to HR; there is **no automatic disqualification**.

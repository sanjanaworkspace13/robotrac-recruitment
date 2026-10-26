# API documentation

Base URL: `http://localhost:8080` (same origin as the frontend). All bodies are JSON (`Content-Type: application/json`)
unless stated. Times are ISO-8601 instants in **UTC** (e.g. `2026-10-23T09:30:00Z`); browsers convert them to local time.

## Conventions

**Authentication.** Server-side HTTP session in the cookie `ROBOTRAC_SESSION` (HttpOnly, SameSite=Lax, Secure in
production). Logging in rotates the session id. Two roles: `CANDIDATE` (paths `/api/candidate/**`) and `ADMIN`
(paths `/api/admin/**`). Everything else under `/api` is public (listed below) or denied.

**CSRF.** Every `POST`, `PUT` and `DELETE` must send header `X-XSRF-TOKEN` with the token from `GET /api/auth/csrf`
(also set as cookie `XSRF-TOKEN`). The frontend's `js/api.js` does this automatically and refreshes the token once
if the server answers `403 CSRF_INVALID`.

**Ownership.** Candidate endpoints contain **no candidate or attempt ids**: the server always uses the logged-in
candidate's own attempt for the drive they logged in to. HR endpoints are reachable only with the ADMIN role, so a
candidate cannot read results by guessing ids (insecure direct object references are impossible by construction).

**Errors.** Every error has the same shape, never with stack traces or SQL:

```json
{ "timestamp": "2026-10-23T09:31:02.123Z", "status": 409, "code": "ATTEMPT_CLOSED",
  "message": "Your test has already been submitted.", "path": "/api/candidate/attempt/submit",
  "fieldErrors": [ { "field": "email", "message": "Please enter your registered email ID." } ],
  "details": { } }
```

| HTTP | Typical `code` values |
|---|---|
| 400 | `VALIDATION_FAILED`, `BAD_REQUEST`, `INVALID_EMAIL`, `INVALID_DRIVE`, `INVALID_DRIVE_CODE`, `WEAK_PASSWORD`, `INVALID_WORKBOOK`, `INVALID_FILE`, `INVALID_EVENT_TYPE`, `INVALID_FILTER`, `TOO_MANY_LINES` |
| 401 | `UNAUTHENTICATED` (no/expired session), `INVALID_CREDENTIALS`, `INVALID_SETUP_TOKEN` |
| 403 | `FORBIDDEN` (wrong role), `CSRF_INVALID`, `NOT_AUTHORIZED`, `DRIVE_NOT_ACTIVE`, `DRIVE_NOT_STARTED` (details.startAt), `DRIVE_ENDED` |
| 404 | `NOT_FOUND`, `NO_ATTEMPT` |
| 409 | `ATTEMPT_CLOSED`, `STALE_UPDATE` (details = current question state), `DUPLICATE_DRIVE_CODE`, `ALREADY_AUTHORIZED`, `ATTEMPT_EXISTS`, `ATTEMPTS_EXIST`, `ACTIVATION_BLOCKED` (details = list), `DRIVE_ACTIVE_LOCKED`, `DRIVE_CLOSED`, `INVALID_STATE`, `QUESTION_IN_USE`, `QUESTION_HAS_ISSUES`, `NO_ANSWER_KEY`, `SETUP_ALREADY_DONE`, `CONCURRENT_UPDATE`, `BUSY`, `CONFLICT` |
| 413 | `FILE_TOO_LARGE` |
| 429 | `RATE_LIMITED` |
| 500 / 503 | `INTERNAL_ERROR`, `DATABASE_UNAVAILABLE`, `PAPER_UNAVAILABLE` |

**Pagination.** List endpoints take `page` (0-based) and `size` (max 200) and return
`{ "items": [...], "page": 0, "size": 25, "totalItems": 120, "totalPages": 5 }`.

---

## Public endpoints

| Method & path | Purpose | Request | Response |
|---|---|---|---|
| `GET /api/health` | Health | – | `200 {status:"UP", application:"UP", database:"UP", serverTime}`; `503` with `database:"DOWN"` |
| `GET /api/auth/csrf` | CSRF token | – | `{headerName:"X-XSRF-TOKEN", token}` |
| `GET /api/auth/me` | Current session | – | `{authenticated:false}` or `{authenticated:true, role:"CANDIDATE", email, driveCode, driveName}` or `{authenticated:true, role:"ADMIN", displayName}` |
| `POST /api/auth/candidate/login` | Candidate login | `{email, driveCode, drivePassword}` (all required; email ≤254, code ≤20, password ≤64) | `200 {email, driveCode, driveName, next:"INSTRUCTIONS"\|"EXAM"}` + session cookie |
| `POST /api/auth/admin/login` | HR login | `{username, password}` | `200 {authenticated:true, role:"ADMIN", displayName}` + session cookie |
| `POST /api/auth/logout` | Log out (either role) | – | `204` |
| `GET /api/setup/status` | Is first-admin setup needed? | – | `{setupRequired:true\|false}` |
| `POST /api/setup/admin` | Create the first admin (only while none exists) | `{setupToken, username (3-60 [A-Za-z0-9._-]), fullName, password}` | `201 {id, username, fullName, enabled, createdAt}` |

**Candidate login rules** (server side, in order): email format → rate limit (failures per email+code and per IP)
→ BCrypt check of the drive password (also done for unknown codes so timing reveals nothing) → email must be
authorised for that drive (not revoked) → *only now* specific reasons: already completed (`409 ATTEMPT_CLOSED`),
attempt in progress (allowed: resume), otherwise the drive must be ACTIVE and inside its window
(`403 DRIVE_NOT_ACTIVE / DRIVE_NOT_STARTED / DRIVE_ENDED`). Wrong code, password or unauthorised email all give the
same `401 INVALID_CREDENTIALS`.

---

## Candidate endpoints (`ROLE_CANDIDATE`)

| Method & path | Purpose | Request | Response |
|---|---|---|---|
| `GET /api/candidate/instructions` | Drive facts for the instructions page | – | `{driveName, driveCode, description, totalQuestions, totalMarks, durationMinutes, availableMinutes, marksPerCorrect, negativeMarkingEnabled, negativeMarks, sections[], awayWarningThreshold, windowEndsAt, serverNow, attemptStatus: null\|"IN_PROGRESS"\|"COMPLETED"}` |
| `POST /api/candidate/attempt/start` | Start (or idempotently return) the single attempt | – | `AttemptState` (below). `409 ATTEMPT_CLOSED` if already completed; `403 DRIVE_*` outside the window; `503 PAPER_UNAVAILABLE` if the bank cannot supply the paper (attempt not consumed) |
| `GET /api/candidate/attempt` | Current state (used on load/refresh) | – | `AttemptState`, or `{status:"COMPLETED", message}`; `404 NO_ATTEMPT` |
| `GET /api/candidate/attempt/clock` | Timer re-sync | – | `{status, serverNow, deadlineAt, remainingSeconds}` |
| `PUT /api/candidate/attempt/questions/{n}/response` | Save an answer (Save & Next; Mark for Review & Next) | `{selectedIndex: 0-3, markedForReview: bool, seq: ≥1}` | `QuestionState` |
| `POST /api/candidate/attempt/questions/{n}/clear` | Clear Response | `{seq}` | `QuestionState` |
| `POST /api/candidate/attempt/questions/{n}/review` | Mark / unmark for review (without answer) | `{markedForReview, seq}` | `QuestionState` |
| `POST /api/candidate/attempt/questions/{n}/visit` | Mark question as visited | – | `QuestionState` |
| `POST /api/candidate/attempt/events` | Report a monitoring event | `{type, clientEventId (8-64 chars [A-Za-z0-9-]), clientTime?, detail? (≤200)}` | `{recorded, duplicate, awayCount, warningThreshold}` |
| `POST /api/candidate/attempt/submit` | Final submit | `{reason:"MANUAL"\|"TIMER"}` (optional) | `{status:"RECORDED", message:"Your examination has been submitted successfully. Your response has been recorded.", recordedAt}` — same answer for repeated calls |

`AttemptState`:
```json
{ "status": "IN_PROGRESS", "driveName": "...", "candidateEmail": "...", "serverNow": "...",
  "startedAt": "...", "deadlineAt": "...", "remainingSeconds": 2380, "totalQuestions": 30,
  "awayCount": 0, "awayWarningThreshold": 3,
  "questions": [ { "number": 1, "section": "Quantitative Aptitude", "text": "...",
                   "options": ["72", "64", "81", "76"], "selectedIndex": null,
                   "markedForReview": false, "visited": false, "lastSeq": 0 } ] }
```
`QuestionState`: `{number, selectedIndex, markedForReview, visited, lastSeq, savedAt, serverNow, remainingSeconds}`.

Notes:
* `options` are already in this candidate's stored order; `selectedIndex` is a **display index**. There is no field
  for the answer key, explanation, difficulty, question id, original letters, score or result.
* `seq` must be larger than the question's `lastSeq`; otherwise `409 STALE_UPDATE` with the current state in
  `details`. The page starts from `max(lastSeq)+1` after every load.
* After the deadline (+2 s grace) every write returns `409 ATTEMPT_CLOSED` and the attempt is finalised.
* Monitoring `type` accepted from the browser: `TAB_HIDDEN, TAB_VISIBLE, WINDOW_BLUR, WINDOW_FOCUS,
  FULLSCREEN_EXIT, PAGE_LOADED, PAGE_UNLOAD`. `LOGIN` and `ATTEMPT_STARTED` are server-only. Duplicate
  `clientEventId`s and the same type repeated within 1 s are acknowledged with `duplicate:true` and not stored.
  Rate limit: 40 events/min per candidate. Extra fields such as a client "count" are ignored.
* Saves: 180 changes/min per candidate.

---

## HR / Admin endpoints (`ROLE_ADMIN`)

### Dashboard, audit, accounts
| Method & path | Purpose | Request | Response |
|---|---|---|---|
| `GET /api/admin/dashboard` | Overview counts + per-drive summary | – | `{totalDrives, activeDrives, liveDrives, authorizedCandidates, attemptsStarted, submitted, timedOut, inProgress, notStarted, questionsEligible, questionsFlagged, drives:[{id, code, name, phase, authorized, started, completed, inProgress, notStarted}], serverNow}` |
| `GET /api/admin/audit?action=&q=&page=&size=` | Audit log (newest first) | – | page of `{id, createdAt, actorType, actorName, action, entityType, entityId, details, ipAddress}` |
| `GET /api/admin/users` | HR accounts | – | `[{id, username, fullName, enabled, createdAt, lastLoginAt}]` |
| `POST /api/admin/users` | Create HR account | `{username, fullName, password}` | `201` account |
| `POST /api/admin/users/me/password` | Change own password | `{currentPassword, newPassword}` | `204` |

### Drives
| Method & path | Purpose | Request / rules | Response |
|---|---|---|---|
| `GET /api/admin/drives` | List | – | `[Drive]` |
| `GET /api/admin/drives/{id}` | One drive | – | `Drive` |
| `POST /api/admin/drives` | Create (DRAFT) | `DriveRequest` | `201 Drive` |
| `PUT /api/admin/drives/{id}` | Edit | DRAFT: everything. ACTIVE: only name, description, endAt, awayWarningThreshold (others must be unchanged → else `409 DRIVE_ACTIVE_LOCKED`). CLOSED: `409` | `Drive` |
| `PUT /api/admin/drives/{id}/password` | Set/replace drive password | `{drivePassword}` (≥8, letters+digits, no spaces) | `Drive` (never contains the password or hash) |
| `GET /api/admin/drives/{id}/availability` | Can the bank supply this drive's mix? | – | `{feasible, problems[], cells:[{section, sectionLabel, difficulty, available, planned}], totalEligible, requested, needsReviewInPool}` |
| `GET /api/admin/drives/availability-preview?easy=&moderate=&hard=&balanced=` | Same, for unsaved form values | – | as above |
| `POST /api/admin/drives/{id}/activate` | DRAFT → ACTIVE | needs password, future end, feasible paper | `{drive, warnings[]}` or `409 ACTIVATION_BLOCKED` |
| `POST /api/admin/drives/{id}/deactivate` | ACTIVE → DRAFT | only if no attempt exists | `Drive` |
| `POST /api/admin/drives/{id}/close` | → CLOSED (irreversible) | – | `Drive` |

`DriveRequest`: `{name (≤150), code (4-20 [A-Z0-9-], stored upper-case, unique), description?, startAt, endAt,
durationMinutes (5-300), easyCount, moderateCount, hardCount (0-100 each, total 1-100), sectionBalanced? (default true),
shuffleOptions? (default true), marksPerCorrect (0.25-10), negativeMarkingEnabled?, negativeMarks? (0-10, ≤ marksPerCorrect),
awayWarningThreshold? (1-50, default 3), drivePassword?}`. Rules: `endAt > startAt`, window ≥ duration, `endAt` in the future.

`Drive`: `{id, name, code, description, startAt, endAt, status: DRAFT|ACTIVE|CLOSED, phase: DRAFT|SCHEDULED|LIVE|ENDED|CLOSED,
durationMinutes, easyCount, moderateCount, hardCount, totalQuestions, sectionBalanced, shuffleOptions, marksPerCorrect, totalMarks,
negativeMarkingEnabled, negativeMarks, awayWarningThreshold, passwordSet, createdBy, createdAt, updatedAt, activatedAt, closedAt,
authorizedCount, attemptsStarted, attemptsCompleted, attemptsInProgress}`.

### Candidates of a drive
| Method & path | Purpose | Request | Response |
|---|---|---|---|
| `GET /api/admin/drives/{driveId}/candidates?q=&page=&size=` | List | – | page of `{authorizationId, candidateId, email, fullName, status: AUTHORIZED\|REVOKED, addedAt, revokedAt, attemptId, attemptStatus: NOT_STARTED\|IN_PROGRESS\|SUBMITTED\|TIMED_OUT}` |
| `POST /api/admin/drives/{driveId}/candidates` | Authorise one (re-authorises a revoked one) | `{email, fullName?}` | `201` row; `409 ALREADY_AUTHORIZED` |
| `POST /api/admin/drives/{driveId}/candidates/bulk` | Authorise a pasted list | `{text}` (lines `email` or `email,full name`; ≤2000 lines) | `{added, reactivated, alreadyAuthorized, invalid:[{line, value, reason}]}` |
| `POST /api/admin/drives/{driveId}/candidates/import` | Same from a CSV file | multipart `file` (.csv/.txt, ≤1 MB, UTF-8) | as bulk |
| `DELETE /api/admin/drives/{driveId}/candidates/{authorizationId}` | Revoke (status change, audited) | – | `204`; `409 ATTEMPT_EXISTS` once the candidate has started |

### Question bank
| Method & path | Purpose | Request | Response |
|---|---|---|---|
| `GET /api/admin/questions?section=&difficulty=&status=&q=&page=&size=` | Search | – | page of `Question` |
| `GET /api/admin/questions/summary` | Counts + eligible matrix | – | `{total, eligible, needsReview, approved, flagged, inactive, cells:[{section, sectionLabel, difficulty, eligible}]}` |
| `GET /api/admin/questions/{id}` | One question | – | `Question` |
| `POST /api/admin/questions/import` | Import workbook | multipart `file` (.xlsx, ≤5 MB) | `{fileName, totalRows, imported, flagged, rejected, skippedExisting, rows:[{row, code, outcome, messages[]}]}` |
| `PUT /api/admin/questions/{id}` | Correct text/options/key (only if never used in an attempt) | `{text, optionA..optionD, correctOption: A-D, explanation?}` | `Question` (status back to NEEDS_REVIEW, or FLAGGED if still invalid) |
| `PUT /api/admin/questions/{id}/review` | Set review status | `{reviewStatus: NEEDS_REVIEW\|APPROVED\|FLAGGED, note?}` | `Question`; approving re-validates (`409 QUESTION_HAS_ISSUES`) |
| `PUT /api/admin/questions/{id}/active` | (De)activate | `{active}` | `Question` |

`Question`: `{id, code, section, sectionLabel, difficulty, text, optionA, optionB, optionC, optionD, correctOption, explanation,
reviewStatus, validationNotes, active, usedInAttempts, sourceRow}`.

### Results and monitoring
| Method & path | Purpose | Request | Response |
|---|---|---|---|
| `GET /api/admin/results?driveId=&status=&q=&sort=&dir=&page=&size=` | Attempts with scores | `sort`: startedAt, submittedAt, score, email, drive, status; `dir`: asc/desc | page of `ResultRow` |
| `GET /api/admin/results/{attemptId}` | Full detail | – | `{summary: ResultRow, reviewNotes, awayWarningThreshold, eventCounts:{TYPE:n}, events:[Event], questions:[{number, code, section, difficulty, text, options[], correctOption, selectedOption, markedForReview, outcome: CORRECT\|INCORRECT\|UNANSWERED}]}` |
| `GET /api/admin/results/{attemptId}/events` | Monitoring history (chronological) | – | `[{id, type, description, source: CLIENT\|SERVER, away, serverTime, clientTime, detail}]` |
| `PUT /api/admin/results/{attemptId}/review` | HR review marker + notes | `{reviewStatus: NOT_REVIEWED\|REVIEWED_NO_CONCERN\|NEEDS_FOLLOW_UP, notes?}` | detail |
| `GET /api/admin/results/export?driveId=&status=&q=` | CSV export (UTF-8 with BOM, formula-injection safe) | – | `text/csv` attachment |

`ResultRow`: `{attemptId, candidateEmail, candidateName, driveId, driveCode, driveName, status, startedAt, deadlineAt,
submittedAt, finalizedBy: CANDIDATE|CLIENT_TIMER|SERVER_DEADLINE, score, maxScore, correct, incorrect, unanswered,
awayCount, awayThresholdExceeded, reviewStatus}` (score fields are `null` while the attempt is in progress).

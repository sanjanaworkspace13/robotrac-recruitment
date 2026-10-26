# Testing checklist

## 1. Automated tests

Run from `backend`: `.\mvnw.cmd test`. Integration tests use an in-memory H2 database in MySQL mode with the real
Flyway migration and a controllable clock, so they never touch your MySQL data.

| Requirement | Test(s) |
|---|---|
| Valid candidate login (normalised email, case-insensitive code) | `AuthAndAccessIntegrationTest.validCandidateLogin` |
| Invalid credentials / unknown code (same generic error) | `wrongPasswordIsRejectedGenerically`, `unknownDriveCodeGivesTheSameGenericError` |
| Unauthorised candidate | `unauthorisedEmailCannotLoginEvenWithCorrectCodeAndPassword` |
| Inactive / not-yet-open / expired drive | `inactiveDraftDriveIsRefused`, `driveNotStartedYetIsRefused`, `expiredDriveIsRefused` |
| Unauthorised admin access; candidate → admin endpoints; admin → candidate endpoints | `adminEndpointsNeedAuthentication`, `candidateCannotUseAdminEndpoints`, `adminCannotUseCandidateEndpoints` |
| Candidate cannot reach another candidate's data | `candidateCannotReachAnotherCandidatesAttempt` |
| CSRF enforced; standard error shape | `postsWithoutCsrfTokenAreRejected`, `csrfEndpointIssuesToken`, `validationErrorsUseTheStandardErrorShape` |
| Health endpoint | `healthEndpointReportsApplicationAndDatabase` |
| Valid import of the real workbook (150 rows, RBT-034 flagged) | `WorkbookReaderTest.readsTheSuppliedQuestionBank`, `FreshDatabaseIntegrationTest.hrImportsTheWorkbookWithValidation` |
| Invalid rows, missing key, invalid option reference, missing columns, non-Excel | `WorkbookReaderTest.*`, `QuestionRowValidatorTest.*` |
| Insufficient questions for the mix | `PaperAllocatorTest.reportsInsufficientDifficulty`, `reportsWhenSectionBalanceIsImpossible`, `DriveManagementIntegrationTest.activationIsBlockedWhenTheBankCannotSupplyTheMix` |
| Paper follows 5/15/10 and 10 per section; flagged question never used | `PaperAllocatorTest.defaultPaperHasExactDifficultyMixAndTenPerSection` (×50), `ExamFlowIntegrationTest.startCreatesOnePaperWithConfiguredDistribution` |
| Correct / incorrect / unanswered scoring; negative marking | `ScoringCalculatorTest.*`, `ExamFlowIntegrationTest.scoringIsDoneOnTheServer` |
| Answer persistence, palette states, clear, review | `answersPersistAndAreRestored` |
| Out-of-order updates rejected | `olderUpdatesCannotOverwriteNewerOnes` |
| Attempt restoration (refresh, repeated start, logout/login) | `refreshAndRepeatedStartKeepTheSameAttemptAndOrder` |
| Timer continues after refresh | `timerContinuesAfterRefresh` |
| Deadline enforcement | `backendRejectsAnswersAfterTheDeadline` |
| Automatic timeout finalisation (browser gone) | `serverFinalisesExpiredAttemptsEvenIfTheBrowserIsGone` |
| Browser timer submit | `clientTimerSubmissionAtDeadlineIsRecordedAsTimedOut` |
| Duplicate submission; one attempt per drive | `submissionIsIdempotentAndASecondAttemptIsImpossible` |
| Concurrent start attempts | `parallelStartRequestsCreateExactlyOneAttempt` |
| Candidate result privacy; answer-key privacy | `candidateCannotSeeResultsEvenAfterSubmission`, `candidateApiNeverExposesAnswerKeysOrScoring` |
| Closing a drive during attempts | `closingADriveLetsAttemptsInProgressFinish` |
| Tab-switch persistence with server time | `MonitoringIntegrationTest.tabSwitchIsPersistedWithServerTimestamp` |
| Duplicate event protection | `retriesAndDoubleFiredBrowserEventsAreNotDoubleCounted` |
| Invalid monitoring requests / client-supplied counts ignored | `browserCannotDictateCountsOrServerOnlyEvents`, `eventsAreRejectedWithoutAnActiveAttempt` |
| Monitoring visible to HR only | `monitoringHistoryIsVisibleToHrOnly`, `reloginDuringAttemptIsRecordedAsServerEvent` |
| Unique drive code; invalid dates | `DriveManagementIntegrationTest.driveCodesAreUniqueIgnoringCase`, `invalidDatesAreRejected` |
| Activation rules; active drive locks settings; closed-drive restrictions; deactivate rules | `hrCanCreateAndActivateADrive`, `activationNeedsADrivePassword`, `activeDriveLocksExamSettings`, `closedDriveRestrictions`, `deactivateOnlyBeforeAnyAttempt` |
| Candidate authorisation, duplicates, malformed, bulk, revoke policy | `candidateAuthorisationRules` |
| First-admin setup (token, strength, one-time) | `FreshDatabaseIntegrationTest.firstAdminSetupIsOneTimeAndTokenProtected` |
| HR results, detail, review, CSV export (formula-safe), used questions not editable, dashboard, audit | `ResultsIntegrationTest.hrSeesScoresDetailsAndCanExportCsv` |
| CSV parsing/escaping, emails, passwords, option mapping, rate limiter | `CoreUtilitiesTest.*` |

## 2. Manual end-to-end test (about 20 minutes)

Use two browsers (or a normal and a private window): one for HR, one for the candidate.

1. **Start**: `.\mvnw.cmd spring-boot:run`; `Invoke-RestMethod http://localhost:8080/api/health` → UP/UP.
2. **Setup**: open `/admin-setup.html`, try a wrong token (refused), then the console token → admin created.
   Reload `/admin-setup.html` → "already complete".
3. **Question bank**: 150 total, 149 eligible, 1 flagged (RBT-034). Open it, Edit option C to a distinct value,
   save, Approve → 150 eligible.
4. **Drive**: create `TEST-01`, window starting now for 2 hours, defaults, Generate password (copy it). Availability says possible. Try Activate before adding candidates → succeeds with a warning.
5. **Candidates**: add your test email; paste a list with one bad line → report shows it.
6. **Unauthorised**: candidate browser, log in with an email not on the list → generic error.
7. **Login**: authorised email + code + password → instructions show 30 questions, 30 marks, 40 min. The Start button stays disabled until the box is ticked.
8. **Exam**: answer Q1 (status says "not saved yet" until Save & Next, then "Saved at …"); Mark for Review on Q2 without answer; answer and mark Q3; use the palette; check the five colours; Clear Response on Q1.
9. **Refresh** (F5): same questions, same option order, saved answers, timer continued.
10. **Tab switch**: switch to another tab and back → yellow warning, count increases. HR → Results → Details shows TAB_HIDDEN/TAB_VISIBLE with times.
11. **Second tab / re-login**: open the exam in another tab or log out and in → same attempt resumes.
12. **Submit**: dialog shows unanswered count → confirm → neutral confirmation only. Log in again → "already completed".
13. **HR results**: score, counts, finalised by candidate, per-question breakdown with keys; set review status; Export CSV opens in Excel.
14. **Timeout**: create a drive with duration 5 min, start as a second candidate, close the browser. After ~5 minutes (+15 s) HR results show TIMED_OUT / "Server at deadline" with a score.
15. **Close drive**: Close → candidates who have not started are refused; results remain.
16. **Audit log**: logins, failed logins, drive actions, imports, export all listed; no passwords anywhere.

## 3. Acceptance criteria status

Legend — **PASS**: verified by actually running it. **NOT VERIFIED**: implemented (code and automated test written
where applicable) but not executed against the real stack, because the generation environment could not
download Maven dependencies or run MySQL. **FAIL**: known not to work. Fill in the last column after you run
`.\mvnw.cmd test` and the manual test above.

| # | Criterion | Status | Evidence / how to verify |
|---|---|---|---|
| 1 | Application starts | NOT VERIFIED | Manual step 1 |
| 2 | MySQL connects | NOT VERIFIED | Health endpoint `database: UP` |
| 3 | Health endpoint responds | NOT VERIFIED | `healthEndpointReportsApplicationAndDatabase`; manual 1 |
| 4 | Secure first admin can log in | NOT VERIFIED | `firstAdminSetupIsOneTimeAndTokenProtected`; manual 2 |
| 5 | HR creates a drive | NOT VERIFIED | `hrCanCreateAndActivateADrive`; manual 4 (UI exercised against a mock API) |
| 6 | HR imports/uses the validated bank | NOT VERIFIED | Workbook parsing logic checked against the real file with Python; `hrImportsTheWorkbookWithValidation` |
| 7 | Distribution feasibility verified | NOT VERIFIED | Allocator verified here against brute force (3,000 cases); `activationIsBlocked…` |
| 8 | HR authorises candidate emails | NOT VERIFIED | `candidateAuthorisationRules`; manual 5 |
| 9 | HR configures and activates a drive | NOT VERIFIED | `hrCanCreateAndActivateADrive`, `activationNeedsADrivePassword` |
| 10 | Authorised candidate logs in | NOT VERIFIED | `validCandidateLogin`; manual 7 |
| 11 | Unauthorised candidate blocked | NOT VERIFIED | `unauthorisedEmail…`; manual 6 |
| 12 | Instructions read and acknowledged | NOT VERIFIED | UI exercised against mock API in headless Chromium; manual 7 |
| 13 | Candidate starts the exam | NOT VERIFIED | `startCreatesOnePaper…`; manual 7 |
| 14 | Exactly one attempt created | NOT VERIFIED | `parallelStartRequestsCreateExactlyOneAttempt`, DB unique constraint |
| 15 | Configured count and difficulty mix | NOT VERIFIED | `startCreatesOnePaperWithConfiguredDistribution`; allocator harness (20,000 papers) |
| 16 | Questions and options randomised | NOT VERIFIED | Allocator harness shows varied plans; option shuffle per question |
| 17 | Answer keys never exposed | NOT VERIFIED | `candidateApiNeverExposesAnswerKeysOrScoring` (checks every JSON key) |
| 18 | Save answers and navigate | NOT VERIFIED | `answersPersistAndAreRestored`; UI exercised against mock API |
| 19 | Palette states accurate | NOT VERIFIED | UI test against mock confirmed the 5 states; manual 8 |
| 20 | Mark for review | NOT VERIFIED | `answersPersistAndAreRestored`; manual 8 |
| 21 | Timer continues after refresh | NOT VERIFIED | `timerContinuesAfterRefresh`; manual 9 |
| 22 | Backend enforces deadline | NOT VERIFIED | `backendRejectsAnswersAfterTheDeadline` |
| 23 | Tab/focus events recorded and reviewable | NOT VERIFIED | `MonitoringIntegrationTest.*`; UI warning exercised against mock |
| 24 | No second attempt | NOT VERIFIED | `submissionIsIdempotentAndASecondAttemptIsImpossible` |
| 25 | Manual submission | NOT VERIFIED | `scoringIsDoneOnTheServer`; manual 12 |
| 26 | Automatic timeout finalisation | NOT VERIFIED | `serverFinalisesExpiredAttempts…`; manual 14 |
| 27 | Results calculated by backend | NOT VERIFIED | `scoringIsDoneOnTheServer`; `ScoringCalculatorTest` (logic verified here) |
| 28 | Neutral confirmation only | NOT VERIFIED | `candidateCannotSeeResultsEvenAfterSubmission` |
| 29 | HR views results | NOT VERIFIED | `hrSeesScoresDetailsAndCanExportCsv` |
| 30 | HR reviews monitoring history | NOT VERIFIED | `monitoringHistoryIsVisibleToHrOnly` |
| 31 | Candidate blocked from HR data | NOT VERIFIED | `candidateCannotUseAdminEndpoints`, `candidateCannotReachAnotherCandidatesAttempt` |
| 32 | Critical rules covered by tests | PASS (written) / NOT RUN | 81 test methods mapped in section 1 |
| 33 | Setup works on clean Windows | NOT VERIFIED | README steps 1–8 |

No criterion is known to FAIL.

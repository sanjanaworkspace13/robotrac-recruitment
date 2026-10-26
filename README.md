# Robotrac Recruitment Portal

A company-level aptitude recruitment portal for **Robotrac**: HR creates recruitment drives, authorises
candidate emails and activates a drive; candidates log in with the drive code and password, take a timed,
randomised 30-question aptitude test in a JEE/NEET-style interface, and receive only a neutral confirmation.
HR sees server-calculated results, exam-integrity events and an audit trail.

| Layer | Technology |
|---|---|
| Frontend | HTML5, CSS3, vanilla JavaScript (Fetch API) — no framework |
| Backend | Java 21, Spring Boot 3.5.16 (Web MVC, Security 6.5, Data JPA/Hibernate 6.6, Bean Validation), Maven |
| Database | MySQL 8, schema managed by Flyway migrations |
| Tests | JUnit 5, Spring Boot Test, MockMvc, Spring Security Test, H2 (MySQL mode, in-memory) |

> **Status: development build, not yet production-ready.** See [Verification status](#verification-status)
> and [Known limitations](#known-limitations). In particular, the Maven build and the automated tests were
> written but could **not be executed in the environment where this project was generated** (Maven Central
> was blocked there). Run them on your machine first: `.\mvnw.cmd test`.

---

## Contents
1. [Project structure](#project-structure)
2. [Setup on Windows (step by step)](#setup-on-windows-step-by-step)
3. [Using the portal](#using-the-portal)
4. [How the important rules work](#how-the-important-rules-work)
5. [Running the tests](#running-the-tests)
6. [Troubleshooting](#troubleshooting)
7. [Going to production](#going-to-production)
8. [Verification status](#verification-status)
9. [Known limitations](#known-limitations)

More detail: [`docs/setup-guide.md`](docs/setup-guide.md) · [`docs/api-documentation.md`](docs/api-documentation.md) ·
[`docs/database-design.md`](docs/database-design.md) · [`docs/testing-checklist.md`](docs/testing-checklist.md)

---

## Project structure

```
robotrac-recruitment/
├── backend/                      Spring Boot application (serves the API AND the frontend)
│   ├── pom.xml                   Dependencies (Spring Boot 3.5.16, Java 21)
│   ├── mvnw, mvnw.cmd            Maven Wrapper (downloads Maven 3.9.11 on first use)
│   ├── config/application.properties.example   → copy to config/application.properties (your DB password)
│   └── src/
│       ├── main/java/com/robotrac/recruitment/
│       │   ├── config/           Security (sessions, CSRF, CORS, headers), web, clock, scheduling
│       │   ├── core/             Pure business logic: paper allocation, scoring, validation, CSV, rate limiting
│       │   ├── domain/           JPA entities and enums
│       │   ├── repository/       Spring Data repositories
│       │   ├── security/         Session principals, JSON 401/403 handlers
│       │   ├── service/          Drives, candidates, question bank import, exam engine, finalisation,
│       │   │                     monitoring, results, dashboard, audit, admin accounts
│       │   ├── exception/        Standard JSON error responses
│       │   └── web/              REST controllers and DTOs
│       ├── main/resources/
│       │   ├── application.properties
│       │   └── db/migration/V1__initial_schema.sql    Flyway migration (the schema)
│       └── test/                 Unit + integration tests (H2) and the real workbook as test data
├── frontend/                     Static pages (served by the backend at http://localhost:8080)
│   ├── candidate-login.html      ← based on the supplied Robotrac_Light_Candidate_Login.html
│   ├── candidate-instructions.html, candidate-exam.html, candidate-submitted.html
│   ├── admin-login.html, admin-setup.html, admin-dashboard.html, drive-management.html,
│   │   candidate-management.html, question-bank.html, results-dashboard.html, audit-log.html, admin-users.html
│   ├── css/  js/  assets/        (assets/robotrac-logo.jpg is the logo extracted from the supplied HTML)
├── database/
│   ├── create-database.sql       One-time: creates the database and the application user
│   ├── schema.sql                Read-only copy of the Flyway schema
│   └── question-bank/Robotrac_Company_Level_Aptitude_Bank_150_40min.xlsx   (your workbook)
├── docs/                         Setup, API, database design, testing checklist
├── .env.example                  Example environment variables (no real secrets)
└── README.md
```

---

## Setup on Windows (step by step)

All commands are for **Windows PowerShell**. Run them one block at a time.

### 1. Install the required software

| Software | Version | How to get it |
|---|---|---|
| JDK | 21 (LTS) | `winget install EclipseAdoptium.Temurin.21.JDK` (or download Temurin 21 from adoptium.net) |
| MySQL Server | 8.0 or 8.4 | `winget install Oracle.MySQL` or the MySQL Installer from dev.mysql.com |
| VS Code | latest | with the "Extension Pack for Java" (recommended extensions are listed in `.vscode/extensions.json`) |
| Maven | not needed | the included Maven Wrapper (`mvnw.cmd`) downloads Maven 3.9.11 automatically |

Close and reopen PowerShell after installing, then check Java:

```powershell
java -version
```

You should see `openjdk version "21...`. If you see an older version or "not recognized", set `JAVA_HOME`:

```powershell
# Adjust the folder name to the one that exists on your computer
setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot"
# then close and reopen PowerShell
```

### 2. Start MySQL and create the database

Check that the MySQL service is running (the name is usually `MySQL80` or `MySQL84`):

```powershell
Get-Service MySQL*
Start-Service MySQL80        # only if it is not "Running"; use the name shown above
```

If `mysql` is not recognised, add its folder to PATH for this window (adjust 8.0/8.4):

```powershell
$env:Path += ";C:\Program Files\MySQL\MySQL Server 8.0\bin"
```

Open `database\create-database.sql` in VS Code and **replace `CHANGE_ME_STRONG_PASSWORD`** with a strong
password you choose. Then, from the `robotrac-recruitment` folder:

```powershell
mysql -u root -p -e "source database/create-database.sql"
```

(PowerShell does not support `<` redirection, which is why `-e "source ..."` is used.) This creates the
database `robotrac_recruitment` and a dedicated user `robotrac_app`. The tables are created automatically by
Flyway when the backend starts.

### 3. Put your database password in a private config file

```powershell
cd backend
Copy-Item config\application.properties.example config\application.properties
notepad config\application.properties
```

Replace `PUT-YOUR-DATABASE-PASSWORD-HERE` with the password from step 2 and save. This file is git-ignored.
(Alternative: `$env:DB_PASSWORD = "your-password"` in the PowerShell window before starting.)

### 4. Build and run the tests

Still inside the `backend` folder:

```powershell
.\mvnw.cmd -v          # first run downloads Maven 3.9.11 (~10 MB)
.\mvnw.cmd test        # runs all unit and integration tests on an in-memory H2 database
```

The tests never touch your MySQL database. A successful run ends with `BUILD SUCCESS`.

### 5. Start the application

```powershell
.\mvnw.cmd spring-boot:run
```

On first start you will see in the console:

1. Flyway creating the tables (`Successfully applied 1 migration`).
2. `Question bank imported from Robotrac_Company_Level_Aptitude_Bank_150_40min.xlsx: 149 imported, 1 flagged for HR review, 0 rejected` — plus a line explaining that **RBT-034** has identical options A and C.
3. A box titled **ROBOTRAC FIRST-TIME SETUP** with a one-time setup token. Keep this window open.

### 6. Check that it is healthy

In a second PowerShell window:

```powershell
Invoke-RestMethod http://localhost:8080/api/health
```

Expected: `status : UP`, `application : UP`, `database : UP`. If the database is down the endpoint returns
HTTP 503 with `database : DOWN`.

### 7. Create the first HR administrator (secure, one time)

Open **http://localhost:8080/admin-setup.html**, paste the setup token from the console, choose a username
and a strong password (12+ characters with upper case, lower case, digit and symbol). The setup page is
permanently disabled once an admin exists. There is no default or hard-coded admin password anywhere.
If you lost the token before using it, simply restart the backend: a new token is printed.

### 8. Open the portal

| Who | URL |
|---|---|
| Candidates | http://localhost:8080/candidate-login.html (also http://localhost:8080/) |
| HR / Admin | http://localhost:8080/admin-login.html |

**Ports:** the backend uses port **8080** and serves the frontend itself, so everything is one origin and no
CORS setup is needed. To use another port: `$env:SERVER_PORT = "9090"` before `.\mvnw.cmd spring-boot:run`
(or `server.port=9090` in `config\application.properties`), and change `BACKEND_URL` in
`frontend/js/config.js` only if you also serve the frontend separately.

**Do not open the HTML files by double-clicking them** (`file://...`): browsers block cookies and API calls
from `file://` pages. Always use the `http://localhost:8080/...` URLs. If you prefer VS Code *Live Server*
(port 5500) for editing the frontend, it works too: open it as `http://localhost:5500/...` (use `localhost`,
not `127.0.0.1`, so the session cookie is shared) — `APP_CORS_ORIGINS` already allows that origin.

To stop the backend press `Ctrl + C` in its window.

---

## Using the portal

### HR workflow
1. **Question Bank** — check the imported bank. The workbook marks all 150 questions "Needs SME review"; they
   are usable but you should approve them (or flag/fix them) before a live drive. RBT-034 is flagged because
   options A and C are identical; fix it with **Edit** (allowed only while it has never been used) and approve.
   You can import a corrected or extended workbook at any time; existing Question IDs are never overwritten.
2. **Drives → New drive** — name, code (e.g. `RBT-2026-OCT`), window, duration (default 40 min), difficulty mix
   (default 5 easy / 15 moderate / 10 hard), marking (+1/0/0 by default, optional negative marking), warning
   threshold and the **drive password** (press *Generate* for a strong one and copy it — it is stored only as
   a BCrypt hash and can never be displayed again; set a new one if it is lost). The form shows live whether
   the bank can supply the mix.
3. **Candidates** — add emails one by one, paste a list, or upload a CSV (`email` or `email,full name` per line).
4. **Activate** the drive. Activation is refused if there is no password, the end time has passed, or the
   bank cannot supply the paper; warnings are shown for an empty candidate list or unreviewed questions.
5. **Share** with each authorised candidate: the login URL, the drive code and the drive password
   (for example by email from your HR mailbox). Knowing the code and password is not enough — the email must
   also be on the drive's list.
6. **Results** — scores, correct/incorrect/unanswered counts, submission type, leave-window counts, the
   per-question breakdown and the exam-integrity timeline. Add an HR review status and notes. **Export CSV**.
7. **Close** the drive when finished (irreversible; results are kept).

### Candidate workflow
Login → instructions (must tick the acknowledgement) → **Start Examination** (this consumes the single
attempt and starts the server timer) → answer with *Save & Next*, *Mark for Review & Next*, *Clear Response*,
*Previous* and the palette → **Submit Test** (with a summary and an unanswered-questions warning) → neutral
confirmation. If the time runs out, the test is submitted automatically.

---

## How the important rules work

| Rule | Where it is enforced |
|---|---|
| Only authorised emails can log in; generic error for wrong code/password/email | `CandidateAuthService` (server), BCrypt for every check, failed attempts rate-limited per account and per IP |
| One attempt per candidate per drive | `UNIQUE(drive_id, candidate_id)` on `exam_attempt` + row lock on the authorisation while starting; parallel or repeated start requests return the same attempt |
| Attempt is consumed when *Start Examination* succeeds | Logging in again only **resumes** the same attempt (same questions, order, options, deadline). No reset button. Genuine technical problems → HR creates a separate make-up drive for that candidate (audited). |
| Server-controlled timer | `deadline = min(start + duration, drive end)` stored at start. The page shows `deadline − server time` and re-syncs every minute. Refreshing, closing the browser or changing the PC clock cannot extend it. |
| Deadline enforcement | Saves are accepted up to 2 s after the deadline (network delay), then rejected. A scheduler finalises expired attempts every 15 s even if the browser is closed; any later request also finalises them. Unsaved answers at the deadline are not counted. |
| Idempotent, race-safe submission | Every submit/timeout runs under the attempt's row lock and does nothing if already final; `exam_result.attempt_id` is UNIQUE. |
| Answer key never reaches the browser | Candidate DTOs have no key/explanation/difficulty fields; options are sent as plain texts in a stored per-candidate order and answered by display index (0–3) only. Tests assert the JSON has no such keys. |
| Results never shown to candidates | Candidate endpoints return only `"Your examination has been submitted successfully. Your response has been recorded."` |
| Out-of-order updates | Every change carries an increasing sequence number; older ones get `409 STALE_UPDATE` and the page shows the latest saved answer. |
| HR closes a drive during attempts | New logins/starts are refused; attempts already in progress continue until their own deadline and are scored normally. |
| Paper composition | Exactly the configured difficulty mix; with section balancing (default) 10 questions per section (e.g. easy 2/2/1, moderate 5/5/5, hard 3/3/4); flagged questions excluded. Feasibility checked before activation with a max-flow test. |
| Monitoring | Tab hidden/visible, window blur/focus, full-screen exit, page load/unload are sent once per transition with a unique id; the server timestamps and counts them; HR sees them; nothing automatic happens to the candidate. |

---

## Running the tests

```powershell
cd backend
.\mvnw.cmd test
```

* **Unit tests** (`src/test/java/.../core`, `WorkbookReaderTest`): paper allocation, scoring, validation of
  question rows, CSV, emails, passwords, rate limiter, and reading the real workbook (150 rows; only RBT-034 flagged).
* **Integration tests** (`src/test/java/.../web`): full Spring context + MockMvc + Spring Security on an
  isolated H2 database with the real Flyway migration: login rules, access control, CSRF, exam flow, scoring,
  privacy of answer keys/results, refresh/resume, deadline, timeout sweeper, idempotent submission, parallel
  starts, monitoring, drive lifecycle, authorisations, workbook upload, first-admin setup, results and CSV export.

Reports: `backend\target\surefire-reports\`. A full map of requirement → test is in
[`docs/testing-checklist.md`](docs/testing-checklist.md).

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `'java' is not recognized` / `JAVA_HOME not found` | Install JDK 21, set `JAVA_HOME` (step 1), reopen PowerShell. |
| `mvnw.cmd` cannot download Maven | Check internet/proxy. Alternatively install Maven manually and use `mvn` instead of `.\mvnw.cmd`. |
| `Access denied for user 'robotrac_app'@'localhost'` | Password in `config\application.properties` differs from the one in `create-database.sql`; or the file was not copied. Start the backend **from the `backend` folder**. |
| `Public Key Retrieval is not allowed` | The default `DB_URL` already contains `allowPublicKeyRetrieval=true`; if you overrode the URL, add it. |
| `Communications link failure` / health shows `database: DOWN` | MySQL service is not running (`Get-Service MySQL*`). |
| `Port 8080 was already in use` | Find it: `netstat -ano \| findstr :8080`, stop that program (`Stop-Process -Id <PID>`), or use another port (`$env:SERVER_PORT="9090"`). |
| Pages show but every action says "security token expired" | You mixed `localhost` and `127.0.0.1`, or opened the page with `file://`. Use `http://localhost:8080/...`. |
| Setup token lost | Restart the backend; a new token is printed while no admin exists. |
| `Validate failed: Migration checksum mismatch` | You edited `V1__initial_schema.sql` after it ran. Never edit an applied migration — add `V2__...sql` instead. For a throw-away dev database you can drop and recreate it. |
| Question bank not imported at startup | The console says where it looked. Start from the `backend` folder, or import the workbook on the Question Bank page. |
| Candidate sees "not open yet" | The drive's window has not started (times are entered in your local time zone and stored in UTC). |

---

## Going to production

This is a development build. Before real use:

* Serve over **HTTPS** behind a reverse proxy; set `SESSION_COOKIE_SECURE=true` and
  `server.forward-headers-strategy=native`; restrict `APP_CORS_ORIGINS` to your real origin (or leave it empty when same-origin).
* Use a managed MySQL with backups; keep the `robotrac_app` user least-privileged.
* Rate limiting and the timeout sweeper are in-memory/per instance: run **one** backend instance, or move
  rate limits to a shared store (e.g. Redis) and keep the sweeper's row-locking (already safe for several instances).
* Have HR and a subject-matter expert approve every question (the workbook's release checklist says so too)
  and run a small pilot.
* Agree data-retention and privacy rules for candidate data and monitoring events.
* Upgrade path: this project pins Spring Boot 3.5.16 (June 2026), chosen because it is a mature, well-documented
  line. The 3.5 line's free open-source support ends around mid-2026, so plan a move to Spring Boot 4.x
  (Spring Framework 7) for long-term security updates, and bump the 3.5 patch version if a newer one exists.

---

## Verification status

What was actually verified while generating this project, and what you need to verify:

| Check | Result |
|---|---|
| All Java sources (main + tests, 98 files) compile as a consistent program | Verified with `javac` against API signature stubs (Spring/Hibernate/POI/JUnit jars could not be downloaded). Real-library compilation: **run `.\mvnw.cmd test`**. |
| Paper allocation algorithm | Verified: 3,000 random bank shapes against a brute-force solver (feasibility identical) and 20,000 random 5/15/10 papers on the real workbook shape (always 10 per section, exact mix, uniform variety). |
| Scoring, CSV escaping, email/password rules | Verified with a plain-Java harness. |
| Workbook schema and content | Verified with Python/openpyxl: 150 rows, 50 per section, 42/73/35 difficulty, keys consistent, RBT-034 has duplicate options. |
| Frontend pages | Every page and JavaScript file syntax-checked; candidate login → instructions → exam (save, mark, palette, tab-switch warning, reload persistence, submit) and all HR pages exercised in headless Chromium **against a mock of the API**, with the same Content-Security-Policy as the backend (no violations, no script errors). |
| Maven build, JUnit tests, MySQL migration, real end-to-end run | **NOT VERIFIED** — please run steps 4–8 above. |

The full acceptance-criteria table (PASS / FAIL / NOT VERIFIED) is in
[`docs/testing-checklist.md`](docs/testing-checklist.md#acceptance-criteria-status).

## Known limitations

* **Browser monitoring is not proctoring.** It sees only this tab/window: it cannot detect phones, other
  devices, screenshots, a second monitor, other people or apps outside the browser; focus can be lost for
  innocent reasons. No webcam, microphone or screen capture is used. Events are for HR review only.
* Unsaved selections are not answers: a selection counts only after *Save & Next* / *Mark for Review & Next*
  is confirmed by the server (stated on the instructions page).
* If the browser is offline at the deadline, answers it could not save are lost; everything confirmed by
  the server before the deadline is scored.
* Rate limiting is per application instance (in memory) — see *Going to production*.
* The question bank cannot be edited for questions already used in attempts (by design, to keep scores
  stable); deactivate and import a corrected copy with a new ID instead.
* HR accounts have a single role (full HR/admin access); there is no separate read-only role yet.
* The supplied logo image includes its own light-grey background and a tagline that is slightly cropped at
  the left and right edges in the original file; it is reused unchanged.

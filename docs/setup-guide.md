# Setup guide (Windows + VS Code)

The short version is in the main [README](../README.md#setup-on-windows-step-by-step). This guide adds the
details a first-time Spring Boot user usually needs. Do one step at a time and check its result before moving on.

## 0. Open the project in VS Code

1. Unzip `robotrac-recruitment.zip` somewhere simple, e.g. `C:\dev\robotrac-recruitment` (avoid OneDrive-synced
   folders: syncing can lock files during builds).
2. VS Code → *File → Open Folder…* → choose `robotrac-recruitment`.
3. Accept the recommended extensions (Java pack, Spring Boot tools, Live Server).
4. Open a terminal: *Terminal → New Terminal* (PowerShell).

## 1. Java 21

```powershell
java -version
```
Must show 21. If several JDKs are installed, `JAVA_HOME` decides which one Maven uses:
```powershell
echo $env:JAVA_HOME
```

## 2. MySQL

* Service running: `Get-Service MySQL*` → Status `Running`.
* Create the database and user once (edit the password in the file first):
  ```powershell
  mysql -u root -p -e "source database/create-database.sql"
  ```
* Check you can log in as the application user:
  ```powershell
  mysql -u robotrac_app -p robotrac_recruitment -e "SELECT 1;"
  ```

## 3. Private configuration

```powershell
cd backend
Copy-Item config\application.properties.example config\application.properties
notepad config\application.properties
```
Settings in this file override `src/main/resources/application.properties`. You can also set environment
variables listed in `.env.example` (e.g. `$env:DB_PASSWORD`). Never put real passwords in files that are committed.

## 4. Build, test, run

```powershell
.\mvnw.cmd test             # unit + integration tests (H2, no MySQL needed)
.\mvnw.cmd spring-boot:run  # start the application on http://localhost:8080
```
Alternative: build a runnable jar and start it (useful for a demo machine):
```powershell
.\mvnw.cmd -DskipTests package
java -jar target\robotrac-recruitment-1.0.0.jar
```
Run the jar **from the `backend` folder** so that `../frontend` and `config\application.properties` are found
(or set `APP_FRONTEND_DIR` / `APP_SEED_FILE` to absolute `file:` paths).

## 5. Database migrations

Flyway applies `V1__initial_schema.sql` automatically on start and records it in `flyway_schema_history`.
To change the schema later, add a **new** file such as `V2__add_column.sql`; never edit an applied migration.

Reset a development database completely (deletes all data):
```powershell
mysql -u root -p -e "DROP DATABASE robotrac_recruitment; CREATE DATABASE robotrac_recruitment CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
```
The next start recreates the tables, re-imports the workbook and prints a new setup token.

## 6. Importing the question bank

* Automatic: on the first start with an empty `question` table, the backend imports
  `database/question-bank/Robotrac_Company_Level_Aptitude_Bank_150_40min.xlsx` (setting `app.question-bank.seed-file`).
  Disable with `APP_SEED_QUESTIONS=false`.
* Manual: HR → *Question Bank* → *Import workbook* (.xlsx). The import report lists every rejected or flagged row.
  Existing Question IDs are never overwritten, so re-importing is safe.

## 7. First admin

While no admin exists, every start prints:
```
 ROBOTRAC FIRST-TIME SETUP - no HR/Admin account exists yet.
 Open  /admin-setup.html  and enter this one-time setup token:
     <token>
```
Only someone who can see the server console can create the first admin. After that, admins create further HR
accounts on *HR Users*. The token is held in memory only and is never stored in the database.

## 8. Drive codes and passwords — HR procedure

1. Create the drive in **Drives**. Choose a code people can type (e.g. `RBT-2026-OCT`).
2. Press **Generate** next to *Drive password* (12 characters, no look-alike characters) and **copy it** into your
   HR password manager. The server stores only a BCrypt hash; nobody (including admins) can read it back.
3. Authorise candidate emails in **Candidates** and activate the drive.
4. Send each candidate, from the official HR mailbox, the login URL, the drive code, the drive password, the
   window and a reminder to use the same email address they applied with.
5. If the password leaks, set a new one on the drive (Edit → Drive password). Candidates already inside the test are
   not affected; others need the new password. All changes appear in the audit log.
6. For a candidate who had a genuine technical failure: there is deliberately **no reset**. Create a small make-up
   drive, authorise only that candidate, and record the reason in the original attempt's HR review notes.

## 9. Serving the frontend separately (optional)

The backend serves `frontend/` itself. If you want to edit the HTML/CSS with VS Code **Live Server**:
1. Start the backend as usual (port 8080).
2. Right-click `frontend/candidate-login.html` → *Open with Live Server*.
3. Make sure the browser address uses `http://localhost:5500` (not `127.0.0.1`).
`frontend/js/config.js` detects that it is not on port 8080 and calls `http://localhost:8080`; the backend's CORS
setting `APP_CORS_ORIGINS` already allows `http://localhost:5500`.

## 10. Ports

| Port | Used by | Change with |
|---|---|---|
| 8080 | backend + frontend | `$env:SERVER_PORT` or `server.port` |
| 3306 | MySQL | `DB_URL` |
| 5500 | optional Live Server | Live Server settings + `APP_CORS_ORIGINS` |

Find what is using a port: `netstat -ano | findstr :8080`, then `Get-Process -Id <PID>`.

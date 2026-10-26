-- =====================================================================
-- One-time MySQL setup for the Robotrac Recruitment Portal.
-- Run as the MySQL root user. BEFORE running, replace CHANGE_ME_STRONG_PASSWORD
-- with a strong password of your own and put the same password in
-- backend\config\application.properties (never commit it).
--
-- PowerShell (from the robotrac-recruitment folder):
--   mysql -u root -p -e "source database/create-database.sql"
--
-- The tables themselves are created automatically by Flyway when the
-- backend starts (backend/src/main/resources/db/migration).
-- =====================================================================

CREATE DATABASE IF NOT EXISTS robotrac_recruitment
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

-- A dedicated application account (do not use root for the application).
CREATE USER IF NOT EXISTS 'robotrac_app'@'localhost' IDENTIFIED BY 'Sanjana@1301';

-- Only the privileges the application and Flyway migrations need, only on this database.
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, DROP
  ON robotrac_recruitment.* TO 'robotrac_app'@'localhost';

FLUSH PRIVILEGES;

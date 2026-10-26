@echo off
@REM ---------------------------------------------------------------------------
@REM Robotrac Maven Wrapper for Windows.
@REM Downloads the Maven version named in .mvn\wrapper\maven-wrapper.properties
@REM (once) into %USERPROFILE%\.m2\wrapper\dists and runs it. Needs Java 21 on
@REM PATH or JAVA_HOME set. Usage (PowerShell, inside the backend folder):
@REM     .\mvnw.cmd -v
@REM     .\mvnw.cmd test
@REM     .\mvnw.cmd spring-boot:run
@REM ---------------------------------------------------------------------------
setlocal

set "WRAPPER_BASEDIR=%~dp0"
set "WRAPPER_PROPS=%WRAPPER_BASEDIR%.mvn\wrapper\maven-wrapper.properties"

if not exist "%WRAPPER_PROPS%" (
  echo [mvnw] Cannot find %WRAPPER_PROPS%
  exit /b 1
)

set "DIST_URL="
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%WRAPPER_PROPS%") do (
  if /i "%%A"=="distributionUrl" set "DIST_URL=%%B"
)
if "%DIST_URL%"=="" (
  echo [mvnw] distributionUrl is missing from maven-wrapper.properties
  exit /b 1
)

for %%F in ("%DIST_URL%") do set "DIST_ZIP=%%~nxF"
set "DIST_NAME=%DIST_ZIP:-bin.zip=%"
set "DIST_ROOT=%USERPROFILE%\.m2\wrapper\dists"
set "MAVEN_HOME_DIR=%DIST_ROOT%\%DIST_NAME%"

if not exist "%MAVEN_HOME_DIR%\bin\mvn.cmd" (
  echo [mvnw] Downloading %DIST_URL%
  if not exist "%DIST_ROOT%" mkdir "%DIST_ROOT%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop';" ^
    "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;" ^
    "$zip=Join-Path $env:TEMP '%DIST_ZIP%';" ^
    "Invoke-WebRequest -UseBasicParsing -Uri '%DIST_URL%' -OutFile $zip;" ^
    "Expand-Archive -Path $zip -DestinationPath '%DIST_ROOT%' -Force;" ^
    "Remove-Item $zip"
  if errorlevel 1 (
    echo [mvnw] Download failed. Check your internet connection or install Maven manually - see README.
    exit /b 1
  )
)

if not exist "%MAVEN_HOME_DIR%\bin\mvn.cmd" (
  echo [mvnw] Maven was not found at %MAVEN_HOME_DIR% after download.
  exit /b 1
)

set "MAVEN_PROJECTBASEDIR=%WRAPPER_BASEDIR:~0,-1%"
call "%MAVEN_HOME_DIR%\bin\mvn.cmd" -f "%WRAPPER_BASEDIR%pom.xml" %*
exit /b %ERRORLEVEL%

@echo off
rem Lore launcher for Windows, in the universal archive (bin\lore.cmd).
rem   lore.cmd                          starts Lore in the foreground
rem   lore.cmd help                     lists the administration commands
rem   lore.cmd reset-password <e-mail>  runs an administration command, then stops
rem Settings: conf\lore.env next to bin\. Java: JAVA_HOME, else java on the PATH.
setlocal EnableExtensions DisableDelayedExpansion

set "LORE_HOME=%~dp0.."
if not defined LORE_ENV_FILE set "LORE_ENV_FILE=%LORE_HOME%\conf\lore.env"
if exist "%LORE_ENV_FILE%" (
  for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%LORE_ENV_FILE%") do set "%%a=%%~b"
)

set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
"%JAVA%" -version >nul 2>&1
if errorlevel 1 (
  echo lore: Java 21 or later is required. Install it, or set JAVA_HOME. 1>&2
  exit /b 1
)

set "JAR=%LORE_HOME%\lib\lore.jar"
set "FIRST=%~1"
if not defined FIRST goto server
if "%FIRST:~0,2%"=="--" goto server
"%JAVA%" -XX:MaxRAMPercentage=25 -XX:TieredStopAtLevel=1 -jar "%JAR%" %*
exit /b %ERRORLEVEL%

:server
if not defined LORE_JAVA_OPTS set "LORE_JAVA_OPTS=-XX:MaxRAMPercentage=25"
"%JAVA%" %LORE_JAVA_OPTS% -jar "%JAR%" %*
exit /b %ERRORLEVEL%

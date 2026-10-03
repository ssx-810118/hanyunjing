@echo off
setlocal
rem Windows launcher for this checkout; requires installed Maven, no wrapper download.
where mvn.cmd >nul 2>nul
if errorlevel 1 (
  echo Maven is not installed or mvn.cmd is not on PATH. Install Maven and use Java 17.
  exit /b 1
)
call mvn.cmd %*
exit /b %ERRORLEVEL%

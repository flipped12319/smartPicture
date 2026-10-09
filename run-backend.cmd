@echo off
setlocal
rem Switch the console to UTF-8 so the application's Chinese log lines render
rem correctly (the app logs in UTF-8; the default code page here is 936).
chcp 65001 >nul
title Smart Picture - Backend (8123)
cd /d "%~dp0"

call find-jdk11.cmd
if errorlevel 1 ( pause & exit /b 1 )

echo.
echo === Starting Backend (pictures / spaces / albums) on port 8123 ===
echo     First run: mvnw downloads Maven automatically, only once.
echo     Press Ctrl+C or close this window to stop.
echo.

cd /d "%~dp0picture-backend"
rem Extra arguments are passed through to Maven, e.g.:
rem     run-backend.cmd -Dspring-boot.run.arguments=--server.port=8128
call mvnw.cmd spring-boot:run %*

echo.
echo Process exited. Scroll up for errors.
pause

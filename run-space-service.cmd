@echo off
setlocal
rem UTF-8 console so the application's Chinese logs render correctly.
chcp 65001 >nul
title Smart Picture - Space Service (8130)
cd /d "%~dp0"

call find-jdk11.cmd
if errorlevel 1 ( pause & exit /b 1 )

echo.
echo === Starting Space Service (spaces / team members / quota) on port 8130 ===
echo     Must run together with the Backend and the User Service.
echo     Press Ctrl+C or close this window to stop.
echo.

cd /d "%~dp0picture-space-service"
rem Extra arguments are passed through to Maven, e.g.:
rem     run-space-service.cmd -Dspring-boot.run.arguments=--server.port=8131
call mvnw.cmd spring-boot:run %*

echo.
echo Process exited. Scroll up for errors.
pause

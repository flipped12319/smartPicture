@echo off
setlocal
rem UTF-8 console so the application's Chinese logs render correctly.
chcp 65001 >nul
title Smart Picture - User Service (8126)
cd /d "%~dp0"

call find-jdk11.cmd
if errorlevel 1 ( pause & exit /b 1 )

echo.
echo === Starting User Service (login / register / user admin) on port 8126 ===
echo     Must run together with the Backend, otherwise login will fail.
echo     Press Ctrl+C or close this window to stop.
echo.

cd /d "%~dp0picture-user-service"
rem Extra arguments are passed through to Maven, e.g.:
rem     run-user-service.cmd -Dspring-boot.run.arguments=--server.port=8127
call mvnw.cmd spring-boot:run %*

echo.
echo Process exited. Scroll up for errors.
pause

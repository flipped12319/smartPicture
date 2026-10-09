@echo off
setlocal
rem UTF-8 console so the application's Chinese logs render correctly.
chcp 65001 >nul
title Smart Picture - Gateway (9000)
cd /d "%~dp0"

call find-jdk11.cmd
if errorlevel 1 ( pause & exit /b 1 )

echo.
echo === Starting Gateway on port 9000 ===
echo     The frontend talks to this, so it must be running.
echo     To enable Nacos service discovery, run instead:
echo         run-gateway.cmd -Dspring-boot.run.profiles=nacos
echo     Press Ctrl+C or close this window to stop.
echo.

cd /d "%~dp0picture-gateway"
rem Extra arguments are passed through to Maven.
call mvnw.cmd spring-boot:run %*

echo.
echo Process exited. Scroll up for errors.
pause

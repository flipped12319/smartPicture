@echo off
setlocal
rem ============================================================
rem Start the monolith with RabbitMQ ENABLED (phase 5: MQ groundwork).
rem
rem NOTE: keep this file ASCII-ONLY. cmd.exe parses .cmd files using the
rem OEM/ANSI code page (936 on Chinese Windows); non-ASCII characters get
rem mangled and the fragments are then executed as commands. Chinese
rem explanations live in docs/local-env.md.
rem
rem Why a separate script instead of editing run-backend.cmd:
rem   MQ is off by default on purpose (see picture.mq.enabled). This script
rem   only turns it on for one run, so the normal start path stays untouched
rem   and cannot break if RabbitMQ is not running.
rem
rem Why port 8128 and not 8123:
rem   You usually already have the backend on 8123. Both can run at the same
rem   time (same DB), so you can compare "MQ on" vs "MQ off" side by side
rem   without stopping your normal backend. Change the port with:
rem       run-backend-mq.cmd 8123
rem
rem Usage:
rem   run-backend-mq.cmd            -> MQ on, debug endpoints on, port 8128
rem   run-backend-mq.cmd 8123       -> same, but on your usual port
rem
rem Logging: starts with logback-file.xml, so the app also writes app.log in
rem picture-backend\. That file is what the verify scripts read
rem (they need -LogFile picture-backend\app.log). Without it, mvnw forks a JVM
rem and the application's log lines never reach your console redirect.
rem ============================================================

chcp 65001 >nul
title Smart Picture - Backend with MQ (RabbitMQ enabled)
cd /d "%~dp0"

call find-jdk11.cmd
if errorlevel 1 ( pause & exit /b 1 )

set "MQ_PORT=%~1"
if "%MQ_PORT%"=="" set "MQ_PORT=8128"

rem --- phase 5 switches -------------------------------------------------
rem picture.mq.enabled=true      declare topology + start consumers
rem picture.mq.debug-enabled=true  expose /mq/debug/** (dev only!)
set "PICTURE_MQ_ENABLED=true"
set "PICTURE_MQ_DEBUG=true"

rem --- RabbitMQ connection (must match docker-compose.yml) ---------------
rem Override any of these before running the script if your setup differs.
if "%RABBITMQ_HOST%"=="" set "RABBITMQ_HOST=127.0.0.1"
if "%RABBITMQ_PORT%"=="" set "RABBITMQ_PORT=5672"
if "%RABBITMQ_USER%"=="" set "RABBITMQ_USER=admin"
if "%RABBITMQ_PASSWORD%"=="" set "RABBITMQ_PASSWORD=change-me-dev-only"

echo.
echo === Starting Backend WITH MQ on port %MQ_PORT% ===
echo     RabbitMQ : %RABBITMQ_HOST%:%RABBITMQ_PORT%   (user %RABBITMQ_USER%)
echo     Console  : http://127.0.0.1:15672
echo     App log  : picture-backend\app.log
echo     MQ debug : POST /api/mq/debug/publish-test , /api/mq/debug/publish-index
echo.
echo     If RabbitMQ is not up yet:  docker compose up -d rabbitmq
echo     Press Ctrl+C or close this window to stop.
echo.

cd /d "%~dp0picture-backend"
call mvnw.cmd spring-boot:run ^
  -Dspring-boot.run.arguments=--server.port=%MQ_PORT% ^
  -Dspring-boot.run.jvmArguments=-Dlogging.config=classpath:logback-file.xml

echo.
echo Process exited. Scroll up for errors.
pause

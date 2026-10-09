@echo off
rem ============================================================
rem Locate a JDK 11 and export it as JAVA_HOME.
rem
rem NOTE: keep this file ASCII-ONLY. cmd.exe parses .cmd files using the
rem OEM/ANSI code page (936 on Chinese Windows). Non-ASCII characters get
rem mangled and the fragments are then executed as commands, which breaks
rem the script in confusing ways. Chinese explanations live in
rem docs/local-env.md instead.
rem
rem Why JDK 11 is required: Spring Boot 2.7.6 ships Lombok 1.18.24, which
rem fails to compile under JDK 21 with:
rem     NoSuchFieldError: Class com.sun.tools.javac.tree.JCTree$JCImport
rem     does not have member field 'com.sun.tools.javac.tree.JCTree qualid'
rem So even with mvnw (no Maven install needed) a JDK 11 is still required.
rem
rem Usage: call find-jdk11.cmd   (JDK11 is set in the caller's session)
rem ============================================================

set "JDK11="

rem 1) JAVA_HOME already pointing at a JDK 11 (path contains "11")
if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\javac.exe" (
    echo %JAVA_HOME% | findstr /c:"11" >nul && set "JDK11=%JAVA_HOME%"
  )
)

rem 2) JDK downloaded by IntelliJ IDEA
if not defined JDK11 if exist "%USERPROFILE%\.jdks\ms-11.0.31\bin\javac.exe" set "JDK11=%USERPROFILE%\.jdks\ms-11.0.31"

rem 3) Other common locations; first match wins
if not defined JDK11 for /d %%d in ("%USERPROFILE%\.jdks\ms-11*") do if not defined JDK11 set "JDK11=%%~fd"
if not defined JDK11 for /d %%d in ("%USERPROFILE%\.jdks\*11*")     do if not defined JDK11 set "JDK11=%%~fd"
if not defined JDK11 for /d %%d in ("C:\Program Files\Java\jdk-11*") do if not defined JDK11 set "JDK11=%%~fd"
if not defined JDK11 for /d %%d in ("C:\Program Files\Eclipse Adoptium\jdk-11*") do if not defined JDK11 set "JDK11=%%~fd"
if not defined JDK11 for /d %%d in ("C:\Program Files\Microsoft\jdk-11*") do if not defined JDK11 set "JDK11=%%~fd"

if not defined JDK11 (
  echo.
  echo [ERROR] JDK 11 not found.
  echo.
  echo   This project requires JDK 11 ^(JDK 21 fails to compile it^).
  echo   Install JDK 11, or edit find-jdk11.cmd and set JDK11 manually:
  echo       set "JDK11=C:\path\to\your\jdk-11"
  echo.
  echo   Current JAVA_HOME = %JAVA_HOME%
  echo.
  exit /b 1
)

set "JAVA_HOME=%JDK11%"
echo [OK] Using JDK 11: %JAVA_HOME%
exit /b 0

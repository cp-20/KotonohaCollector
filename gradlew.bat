@rem Minimal Gradle wrapper launcher for Windows
@if "%DEBUG%"=="" @echo off
setlocal
set APP_HOME=%~dp0

if defined JAVA_HOME goto findJavaFromJavaHome
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto execute
echo JAVA_HOME is not set and no java command could be found. 1>&2
exit /b 1

:findJavaFromJavaHome
set JAVA_EXE=%JAVA_HOME%\bin\java.exe
if exist "%JAVA_EXE%" goto execute
echo JAVA_HOME points to an invalid directory: %JAVA_HOME% 1>&2
exit /b 1

:execute
"%JAVA_EXE%" %JAVA_OPTS% %GRADLE_OPTS% -Dorg.gradle.appname=gradlew -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
endlocal

@rem Gradle wrapper para Windows.
@rem Se gradle\wrapper\gradle-wrapper.jar nao existir, abra o projeto no Android Studio
@rem (ele baixa o wrapper automaticamente) ou rode: gradle wrapper --gradle-version 8.7
@if "%DEBUG%"=="" @echo off
setlocal
set DIRNAME=%~dp0
set APP_HOME=%DIRNAME%
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
if not exist "%CLASSPATH%" (
  echo [ERRO] gradle\wrapper\gradle-wrapper.jar nao encontrado.
  echo Abra o projeto no Android Studio ou rode: gradle wrapper --gradle-version 8.7
  exit /b 1
)
if defined JAVA_HOME (set JAVA_EXE=%JAVA_HOME%\bin\java.exe) else (set JAVA_EXE=java.exe)
"%JAVA_EXE%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
endlocal

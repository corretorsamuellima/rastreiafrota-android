#!/bin/sh
# Gradle wrapper (Unix). Requer gradle/wrapper/gradle-wrapper.jar — o Android Studio
# gera automaticamente na importação, ou rode: gradle wrapper --gradle-version 8.7
APP_HOME=$(cd "$(dirname "$0")" && pwd)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$CLASSPATH" ]; then
  echo "[ERRO] gradle/wrapper/gradle-wrapper.jar não encontrado."
  echo "Abra o projeto no Android Studio ou rode: gradle wrapper --gradle-version 8.7"
  exit 1
fi
JAVA_EXE="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$JAVA_EXE" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"

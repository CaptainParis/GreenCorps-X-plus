#!/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
exec java -Xmx2G -classpath "$DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"

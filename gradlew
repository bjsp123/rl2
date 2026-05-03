#!/usr/bin/env sh

# Copyright 2015 the original author or authors.
# Licensed under the Apache License, Version 2.0

APP_NAME="Gradle"
APP_BASE_NAME=${0##*/}
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

app_path=$0
while [ -h "$app_path" ] ; do
    ls=$(ls -ld "$app_path")
    link=${ls#*' -> '}
    case $link in
        /*) app_path=$link ;;
        *) app_path=$(dirname "$app_path")/$link ;;
    esac
done
APP_HOME=$(cd "$(dirname "$app_path")" && pwd -P) || exit

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ] ; then
    JAVACMD=$JAVA_HOME/bin/java
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
        exit 1
    fi
else
    JAVACMD=java
    command -v java >/dev/null 2>&1 || { echo "ERROR: java not found in PATH." >&2; exit 1; }
fi

# Build arg list: first the fixed args (including main class), then original args
set -- \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"

# Prepend JVM opts from DEFAULT_JVM_OPTS / JAVA_OPTS / GRADLE_OPTS
eval "set -- $(
    printf '%s\n' "$DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS" |
    xargs -n1 |
    sed ' s~[^a-zA-Z0-9/=@_-]~\\&~g; ' |
    tr '\n' ' '
) $@"

exec "$JAVACMD" "$@"

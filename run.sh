#!/bin/bash
cd "$(dirname "$0")"
JAVA="${HOME}/.local/lib/jdk-21/bin/java"
[ -x "$JAVA" ] || JAVA="java"
"$JAVA" -jar build/libs/google-calendar-mcp-all.jar

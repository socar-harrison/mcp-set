#!/bin/bash
cd "$(dirname "$0")"
source .env 2>/dev/null
java -jar build/libs/google-calendar-mcp-all.jar

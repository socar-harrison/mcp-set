#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
JAR_PATH="$SCRIPT_DIR/build/libs/protocol-codegen-mcp-all.jar"

if [[ -n "${JAVA_BIN:-}" ]]; then
  JAVA_EXECUTABLE="$JAVA_BIN"
else
  JAVA_EXECUTABLE="$(command -v java || true)"
fi

[[ -n "$JAVA_EXECUTABLE" && -x "$JAVA_EXECUTABLE" ]] || {
  printf 'Java 21 is required. Set JAVA_BIN or add java to PATH.\n' >&2
  exit 1
}

JAVA_VERSION_OUTPUT="$({ "$JAVA_EXECUTABLE" -version; } 2>&1)" || {
  printf 'Could not run: %s -version\n' "$JAVA_EXECUTABLE" >&2
  exit 1
}
JAVA_VERSION="$(printf '%s\n' "$JAVA_VERSION_OUTPUT" | sed -n '1s/.*version "\([^"]*\)".*/\1/p')"
JAVA_MAJOR="${JAVA_VERSION%%.*}"
[[ "$JAVA_MAJOR" == "21" ]] || {
  printf 'Java 21 is required (found: %s).\n' "${JAVA_VERSION:-unknown}" >&2
  exit 1
}

[[ -f "$JAR_PATH" ]] || {
  printf 'Missing %s. Run ./gradlew shadowJar first.\n' "$JAR_PATH" >&2
  exit 1
}

exec "$JAVA_EXECUTABLE" -jar "$JAR_PATH"

#!/usr/bin/env bash
set -Eeuo pipefail

readonly REPOSITORY="socar-harrison/mcp-set"
readonly SERVER_NAME="socar-protocol-codegen"
readonly JAR_NAME="protocol-codegen-mcp-all.jar"
readonly CHECKSUM_NAME="${JAR_NAME}.sha256"
readonly SKILL_ASSET_NAME="socar-protocol-codegen.SKILL.md"
readonly SKILL_CHECKSUM_NAME="${SKILL_ASSET_NAME}.sha256"

INSTALL_DIR="${MCP_INSTALL_DIR:-${HOME:?HOME is not set}/.local/share/socar-protocol-codegen-mcp}"
RELEASE_VERSION="${MCP_VERSION:-latest}"
PROJECT_DIR=""
CONFIGURE_CLIENTS=true

usage() {
  cat <<'USAGE'
Usage:
  ./install.sh --project-dir /absolute/path/to/socar-android-library [options]
  ./install.sh --no-client-config [options]

Options:
  --project-dir PATH    Android workspace used for project-scoped client setup
  --version TAG         GitHub release tag (default: latest)
  --install-dir PATH    JAR install directory
  --no-client-config    Install the verified JAR without configuring clients
  -h, --help            Show this help

Environment:
  JAVA_BIN              Java 21 executable (default: java found on PATH)
  MCP_VERSION           Release tag used when --version is omitted
  MCP_INSTALL_DIR       Install directory used when --install-dir is omitted
USAGE
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

while (($# > 0)); do
  case "$1" in
    --project-dir)
      (($# >= 2)) || fail "--project-dir requires a path"
      PROJECT_DIR="$2"
      shift 2
      ;;
    --version)
      (($# >= 2)) || fail "--version requires a release tag"
      RELEASE_VERSION="$2"
      shift 2
      ;;
    --install-dir)
      (($# >= 2)) || fail "--install-dir requires a path"
      INSTALL_DIR="$2"
      shift 2
      ;;
    --no-client-config)
      CONFIGURE_CLIENTS=false
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      fail "unknown option: $1"
      ;;
  esac
done

[[ "$RELEASE_VERSION" == "latest" || "$RELEASE_VERSION" =~ ^v[0-9][0-9A-Za-z._-]*$ ]] || \
  fail "release tag must be 'latest' or start with v and contain only letters, digits, '.', '_' or '-'"

if [[ "$CONFIGURE_CLIENTS" == true ]]; then
  [[ -n "$PROJECT_DIR" ]] || fail "--project-dir is required unless --no-client-config is used"
  [[ -d "$PROJECT_DIR" ]] || fail "project directory does not exist: $PROJECT_DIR"
  PROJECT_DIR="$(cd -- "$PROJECT_DIR" && pwd -P)"
  [[ -d "$PROJECT_DIR/.git" || -f "$PROJECT_DIR/.git" ]] || \
    fail "project directory is not a Git workspace: $PROJECT_DIR"
  [[ -f "$PROJECT_DIR/tools/api2-model/generate.kts" ]] || \
    fail "tools/api2-model/generate.kts was not found in: $PROJECT_DIR"
fi

command -v curl >/dev/null 2>&1 || fail "curl is required"

if [[ -n "${JAVA_BIN:-}" ]]; then
  [[ -x "$JAVA_BIN" ]] || fail "JAVA_BIN is not executable: $JAVA_BIN"
  JAVA_EXECUTABLE="$(cd -- "$(dirname -- "$JAVA_BIN")" && pwd -P)/$(basename -- "$JAVA_BIN")"
else
  JAVA_COMMAND="$(command -v java || true)"
  [[ -n "$JAVA_COMMAND" ]] || fail "Java 21 is required. Install it first or set JAVA_BIN."
  JAVA_EXECUTABLE="$(cd -- "$(dirname -- "$JAVA_COMMAND")" && pwd -P)/$(basename -- "$JAVA_COMMAND")"
fi

JAVA_VERSION_OUTPUT="$({ "$JAVA_EXECUTABLE" -version; } 2>&1)" || fail "failed to run: $JAVA_EXECUTABLE -version"
JAVA_VERSION="$(printf '%s\n' "$JAVA_VERSION_OUTPUT" | sed -n '1s/.*version "\([^"]*\)".*/\1/p')"
JAVA_MAJOR="${JAVA_VERSION%%.*}"
[[ "$JAVA_MAJOR" == "21" ]] || fail "Java 21 is required (found: ${JAVA_VERSION:-unknown})"

mkdir -p -- "$INSTALL_DIR"
INSTALL_DIR="$(cd -- "$INSTALL_DIR" && pwd -P)"
JAR_PATH="$INSTALL_DIR/$JAR_NAME"
PREVIOUS_JAR_PATH="$JAR_PATH.previous"

if [[ "$RELEASE_VERSION" == "latest" ]]; then
  RELEASE_BASE_URL="https://github.com/$REPOSITORY/releases/latest/download"
else
  RELEASE_BASE_URL="https://github.com/$REPOSITORY/releases/download/$RELEASE_VERSION"
fi

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/socar-protocol-codegen.XXXXXX")"
STAGED_JAR=""
STAGED_PREVIOUS=""
cleanup() {
  local exit_code=$?
  [[ -z "$STAGED_JAR" || ! -e "$STAGED_JAR" ]] || rm -f -- "$STAGED_JAR"
  [[ -z "$STAGED_PREVIOUS" || ! -e "$STAGED_PREVIOUS" ]] || rm -f -- "$STAGED_PREVIOUS"
  rm -rf -- "$TEMP_DIR"
  exit "$exit_code"
}
trap cleanup EXIT

printf 'Downloading %s (%s)...\n' "$JAR_NAME" "$RELEASE_VERSION"
curl --fail --location --show-error --silent --proto '=https' --proto-redir '=https' \
  "$RELEASE_BASE_URL/$JAR_NAME" \
  --output "$TEMP_DIR/$JAR_NAME"
curl --fail --location --show-error --silent --proto '=https' --proto-redir '=https' \
  "$RELEASE_BASE_URL/$CHECKSUM_NAME" \
  --output "$TEMP_DIR/$CHECKSUM_NAME"
if [[ "$CONFIGURE_CLIENTS" == true ]]; then
  curl --fail --location --show-error --silent --proto '=https' --proto-redir '=https' \
    "$RELEASE_BASE_URL/$SKILL_ASSET_NAME" \
    --output "$TEMP_DIR/$SKILL_ASSET_NAME"
  curl --fail --location --show-error --silent --proto '=https' --proto-redir '=https' \
    "$RELEASE_BASE_URL/$SKILL_CHECKSUM_NAME" \
    --output "$TEMP_DIR/$SKILL_CHECKSUM_NAME"
fi

sha256_of() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{ print $1 }'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{ print $1 }'
  else
    fail "sha256sum or shasum is required"
  fi
}

verify_asset() {
  local asset="$1"
  local checksum="$2"
  local expected
  local actual

  expected="$(awk 'NR == 1 { print $1 }' "$checksum" | tr '[:upper:]' '[:lower:]')"
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || fail "invalid checksum file for: $(basename -- "$asset")"
  actual="$(sha256_of "$asset" | tr '[:upper:]' '[:lower:]')"
  [[ "$actual" == "$expected" ]] || \
    fail "SHA-256 verification failed for $(basename -- "$asset"); installed files were not changed"
}

verify_asset "$TEMP_DIR/$JAR_NAME" "$TEMP_DIR/$CHECKSUM_NAME"
if [[ "$CONFIGURE_CLIENTS" == true ]]; then
  verify_asset "$TEMP_DIR/$SKILL_ASSET_NAME" "$TEMP_DIR/$SKILL_CHECKSUM_NAME"
fi

# Stage in the destination directory so the final rename is atomic on one filesystem.
STAGED_JAR="$(mktemp "$INSTALL_DIR/.${JAR_NAME}.new.XXXXXX")"
cp -- "$TEMP_DIR/$JAR_NAME" "$STAGED_JAR"
chmod 0644 "$STAGED_JAR"

if [[ -f "$JAR_PATH" ]]; then
  STAGED_PREVIOUS="$(mktemp "$INSTALL_DIR/.${JAR_NAME}.previous.XXXXXX")"
  cp -p -- "$JAR_PATH" "$STAGED_PREVIOUS"
  mv -f -- "$STAGED_PREVIOUS" "$PREVIOUS_JAR_PATH"
  STAGED_PREVIOUS=""
fi

mv -f -- "$STAGED_JAR" "$JAR_PATH"
STAGED_JAR=""
printf 'Verified and installed: %s\n' "$JAR_PATH"
[[ ! -f "$PREVIOUS_JAR_PATH" ]] || printf 'Previous JAR retained: %s\n' "$PREVIOUS_JAR_PATH"

install_skill_for_client() {
  local client_home="$1"
  local skill_dir="$client_home/skills/socar-protocol-codegen"
  local skill_path="$skill_dir/SKILL.md"
  local staged_skill

  mkdir -p -- "$skill_dir"
  staged_skill="$(mktemp "$skill_dir/.SKILL.md.new.XXXXXX")"
  cp -- "$TEMP_DIR/$SKILL_ASSET_NAME" "$staged_skill"
  chmod 0644 "$staged_skill"
  if [[ -f "$skill_path" ]]; then
    cp -p -- "$skill_path" "$skill_path.previous"
  fi
  mv -f -- "$staged_skill" "$skill_path"
  printf 'Installed shared skill: %s\n' "$skill_path"
}

if [[ "$CONFIGURE_CLIENTS" == true ]]; then
  CODEX_SKILL_HOME="${CODEX_HOME:-${HOME:?}/.codex}"
  CLAUDE_SKILL_HOME="${CLAUDE_CONFIG_DIR:-$HOME/.claude}"
  if [[ -d "$CODEX_SKILL_HOME" ]] || command -v codex >/dev/null 2>&1; then
    install_skill_for_client "$CODEX_SKILL_HOME"
  fi
  if [[ -d "$CLAUDE_SKILL_HOME" ]] || command -v claude >/dev/null 2>&1; then
    install_skill_for_client "$CLAUDE_SKILL_HOME"
  fi
fi

client_command_hint() {
  local client="$1"
  if [[ "$client" == "claude" ]]; then
    printf '  cd %q && claude mcp add --scope project %q -e %q -- %q -jar %q\n' \
      "$PROJECT_DIR" "$SERVER_NAME" "SOCAR_ANDROID_WORKSPACE=$PROJECT_DIR" "$JAVA_EXECUTABLE" "$JAR_PATH"
  else
    printf '  codex mcp add %q --env %q -- %q -jar %q\n' \
      "$SERVER_NAME" "SOCAR_ANDROID_WORKSPACE=$PROJECT_DIR" "$JAVA_EXECUTABLE" "$JAR_PATH"
  fi
}

configure_claude() {
  if ! command -v claude >/dev/null 2>&1; then
    printf 'Claude Code CLI not found; skipped client configuration.\n'
    client_command_hint claude
    return
  fi

  local help_output
  help_output="$(claude mcp add --help 2>&1 || true)"
  if [[ "$help_output" != *"--scope <scope>"* ]]; then
    printf 'Claude Code MCP command format is not recognized; existing configuration was not changed.\n' >&2
    client_command_hint claude
    return
  fi

  if (cd -- "$PROJECT_DIR" && claude mcp get "$SERVER_NAME" >/dev/null 2>&1); then
    printf 'Claude Code already has %s; preserved the existing configuration.\n' "$SERVER_NAME"
    return
  fi

  if (cd -- "$PROJECT_DIR" && claude mcp add --scope project "$SERVER_NAME" \
    -e "SOCAR_ANDROID_WORKSPACE=$PROJECT_DIR" -- "$JAVA_EXECUTABLE" -jar "$JAR_PATH"); then
    printf 'Configured Claude Code at project scope: %s\n' "$PROJECT_DIR"
  else
    printf 'Claude Code configuration failed; the verified JAR remains installed. Run manually:\n' >&2
    client_command_hint claude
  fi
}

configure_codex() {
  if ! command -v codex >/dev/null 2>&1; then
    printf 'Codex CLI not found; skipped client configuration.\n'
    client_command_hint codex
    return
  fi

  local help_output
  help_output="$(codex mcp add --help 2>&1 || true)"
  if [[ "$help_output" != *"<NAME>"* || "$help_output" != *"<COMMAND>"* ]]; then
    printf 'Codex MCP command format is not recognized; existing configuration was not changed.\n' >&2
    client_command_hint codex
    return
  fi

  if codex mcp get "$SERVER_NAME" --json >/dev/null 2>&1; then
    printf 'Codex already has %s; preserved the existing configuration.\n' "$SERVER_NAME"
    return
  fi

  if codex mcp add "$SERVER_NAME" --env "SOCAR_ANDROID_WORKSPACE=$PROJECT_DIR" -- \
    "$JAVA_EXECUTABLE" -jar "$JAR_PATH"; then
    printf 'Configured Codex (current CLI mcp add scope: user).\n'
  else
    printf 'Codex configuration failed; the verified JAR remains installed. Run manually:\n' >&2
    client_command_hint codex
  fi
}

if [[ "$CONFIGURE_CLIENTS" == true ]]; then
  configure_claude
  configure_codex
else
  printf 'Client configuration skipped by request.\n'
fi

printf '\nInstallation complete. Restart configured MCP clients before using the server.\n'

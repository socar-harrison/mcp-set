#!/bin/bash
set -e

REPO="socar-harrison/mcp-set"
JAR_NAME="google-calendar-mcp-all.jar"
INSTALL_DIR="$HOME/.local/lib"
JAR_PATH="$INSTALL_DIR/$JAR_NAME"
JDK_DIR="$INSTALL_DIR/jdk-21"
JAVA_BIN="$JDK_DIR/bin/java"

echo "=== Google Calendar MCP Server 설치 ==="
echo ""

# 1. OS/아키텍처 감지
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)

case "$OS" in
  darwin) ADOPTIUM_OS="mac" ;;
  linux)  ADOPTIUM_OS="linux" ;;
  *)
    echo "❌ 지원하지 않는 OS입니다: $OS"
    exit 1
    ;;
esac

case "$ARCH" in
  arm64|aarch64) ADOPTIUM_ARCH="aarch64" ;;
  x86_64|amd64)  ADOPTIUM_ARCH="x64" ;;
  *)
    echo "❌ 지원하지 않는 아키텍처입니다: $ARCH"
    exit 1
    ;;
esac

# 2. 격리된 JDK 21 설치 (시스템 Java에 영향 없음)
if [ -x "$JAVA_BIN" ] && "$JAVA_BIN" -version 2>&1 | grep -q '"21\.'; then
  echo "[1/3] JDK 21 ✓ (이미 설치됨)"
else
  echo "[1/3] JDK 21 다운로드 중... (~200MB, 최초 1회만)"
  mkdir -p "$INSTALL_DIR"

  DOWNLOAD_URL="https://api.adoptium.net/v3/binary/latest/21/ga/${ADOPTIUM_OS}/${ADOPTIUM_ARCH}/jdk/hotspot/normal/eclipse?project=jdk"

  TMP_TAR=$(mktemp /tmp/jdk-21-XXXXXX.tar.gz)
  TMP_EXTRACT=$(mktemp -d /tmp/jdk-extract-XXXXXX)
  trap 'rm -rf "$TMP_TAR" "$TMP_EXTRACT"' EXIT

  curl -sL "$DOWNLOAD_URL" -o "$TMP_TAR"
  tar xzf "$TMP_TAR" -C "$TMP_EXTRACT"

  # bin/java를 찾아서 JDK 홈 디렉토리를 역추적 (구조에 무관하게 동작)
  JAVA_FOUND=$(find "$TMP_EXTRACT" -path "*/bin/java" -type f | head -1)
  if [ -z "$JAVA_FOUND" ]; then
    echo "❌ JDK에서 java 바이너리를 찾을 수 없습니다."
    exit 1
  fi
  JDK_HOME=$(dirname "$(dirname "$JAVA_FOUND")")

  rm -rf "$JDK_DIR"
  mv "$JDK_HOME" "$JDK_DIR"

  echo "      → $JDK_DIR"
fi

# 3. JAR 다운로드
echo "[2/3] 최신 JAR 다운로드 중..."
mkdir -p "$INSTALL_DIR"

JAR_URL=$(curl -sL "https://api.github.com/repos/$REPO/releases/latest" \
  | grep "browser_download_url.*$JAR_NAME" \
  | cut -d '"' -f 4)

if [ -z "$JAR_URL" ]; then
  echo "❌ 릴리스에서 JAR을 찾을 수 없습니다."
  echo "   https://github.com/$REPO/releases 를 확인해주세요."
  exit 1
fi

curl -sL "$JAR_URL" -o "$JAR_PATH"
echo "      → $JAR_PATH"

# 4. Claude Code에 MCP 서버 등록 (격리된 Java로 실행)
echo "[3/3] Claude Code에 MCP 서버 등록 중..."
if command -v claude &>/dev/null; then
  claude mcp remove google-calendar 2>/dev/null || true
  claude mcp add google-calendar -s user -- "$JAVA_BIN" -jar "$JAR_PATH"
  echo ""
  echo "=== 설치 완료! ==="
  echo ""
  echo "Claude Code를 재시작한 뒤, 캘린더 관련 질문을 하면"
  echo "브라우저가 열리며 Google 인증이 진행됩니다."
  echo "(@socar.kr 계정으로 로그인하세요)"
else
  echo ""
  echo "=== JAR 다운로드 완료! ==="
  echo ""
  echo "Claude Code가 설치되어 있지 않습니다."
  echo "Claude Code 설치 후 아래 명령어를 실행해주세요:"
  echo ""
  echo "  claude mcp add google-calendar -s user -- $JAVA_BIN -jar $JAR_PATH"
fi

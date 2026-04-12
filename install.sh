#!/bin/bash
set -e

REPO="socar-harrison/mcp-set"
JAR_NAME="google-calendar-mcp-all.jar"
INSTALL_DIR="$HOME/.local/lib"
JAR_PATH="$INSTALL_DIR/$JAR_NAME"

echo "=== Google Calendar MCP Server 설치 ==="
echo ""

# 1. Java 확인 및 설치
if ! command -v java &>/dev/null; then
  echo "[1/3] Java가 없습니다. 설치 중..."
  if command -v brew &>/dev/null; then
    brew install openjdk@21
    sudo ln -sfn "$(brew --prefix openjdk@21)/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-21.jdk
  else
    echo "❌ Homebrew가 없어 Java를 자동 설치할 수 없습니다."
    echo "   brew install openjdk@21 또는 https://adoptium.net 에서 설치해주세요."
    exit 1
  fi
else
  echo "[1/3] Java ✓ ($(java --version 2>&1 | head -1))"
fi

# 2. JAR 다운로드
echo "[2/3] 최신 JAR 다운로드 중..."
mkdir -p "$INSTALL_DIR"

DOWNLOAD_URL=$(curl -sL "https://api.github.com/repos/$REPO/releases/latest" \
  | grep "browser_download_url.*$JAR_NAME" \
  | cut -d '"' -f 4)

if [ -z "$DOWNLOAD_URL" ]; then
  echo "❌ 릴리스에서 JAR을 찾을 수 없습니다."
  echo "   https://github.com/$REPO/releases 를 확인해주세요."
  exit 1
fi

curl -sL "$DOWNLOAD_URL" -o "$JAR_PATH"
echo "   → $JAR_PATH"

# 3. Claude Code에 MCP 서버 등록
echo "[3/3] Claude Code에 MCP 서버 등록 중..."
if command -v claude &>/dev/null; then
  claude mcp remove google-calendar 2>/dev/null || true
  claude mcp add google-calendar -s user -- java -jar "$JAR_PATH"
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
  echo "  claude mcp add google-calendar -s user -- java -jar $JAR_PATH"
fi

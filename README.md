# Google Calendar MCP Server

SOCAR 앱 배포 캘린더를 Claude Code에서 조회할 수 있는 MCP 서버입니다.

## 설치 (원라인)

```bash
curl -sL https://raw.githubusercontent.com/socar-harrison/mcp-set/main/install.sh | bash
```

이 스크립트가 자동으로:
1. JDK 21을 `~/.local/lib/jdk-21/`에 격리 설치 (시스템 Java에 영향 없음)
2. 최신 JAR 다운로드
3. Claude Code에 MCP 서버 등록

> Homebrew, sudo 불필요. 기존 Java 환경에 영향을 주지 않습니다.

## 수동 설치

### 1. JAR 다운로드

[Releases](https://github.com/socar-harrison/mcp-set/releases/latest) 에서 `google-calendar-mcp-all.jar`를 다운로드합니다.

```bash
mkdir -p ~/.local/lib
curl -sL https://github.com/socar-harrison/mcp-set/releases/latest/download/google-calendar-mcp-all.jar \
  -o ~/.local/lib/google-calendar-mcp-all.jar
```

### 2. JDK 21 설치

이미 `~/.local/lib/jdk-21/`이 있다면 이 단계는 건너뛰세요.

```bash
# macOS Apple Silicon 기준. Intel은 aarch64 → x64로 변경
curl -sL "https://api.adoptium.net/v3/binary/latest/21/ga/mac/aarch64/jdk/hotspot/normal/eclipse?project=jdk" \
  -o /tmp/jdk-21.tar.gz
mkdir -p /tmp/jdk-extract && tar xzf /tmp/jdk-21.tar.gz -C /tmp/jdk-extract
mv /tmp/jdk-extract/jdk-*/Contents/Home ~/.local/lib/jdk-21
rm -rf /tmp/jdk-21.tar.gz /tmp/jdk-extract
```

### 3. Claude Code에 MCP 서버 등록

```bash
claude mcp add google-calendar -s user -- ~/.local/lib/jdk-21/bin/java -jar ~/.local/lib/google-calendar-mcp-all.jar
```

### 4. Claude Code 재시작

Claude Code를 종료 후 다시 시작합니다.

## 최초 실행 (Google 인증)

Claude Code에서 캘린더 관련 요청을 하면 MCP 서버가 시작되면서 **브라우저가 자동으로 열립니다.**

1. @socar.kr 계정으로 Google 로그인
2. 캘린더 읽기 권한 동의
3. "Calendar MCP 인증 완료!" 페이지가 뜨면 브라우저를 닫기

토큰은 `~/.calendar-mcp/token.json`에 저장되며, 이후부터는 자동 갱신됩니다.

## 사용 가능한 도구

| 도구 | 설명 |
|------|------|
| `list_calendars` | 접근 가능한 캘린더 목록 조회 |
| `get_deploy_schedule` | 앱 배포 캘린더 일정 조회 (기본 30일) |
| `search_events` | 키워드로 캘린더 이벤트 검색 |
| `get_healing_room` | 4층/5층 힐링룸 예약 현황 및 빈 시간대 조회 |
| `book_healing_room` | 힐링룸 예약 (30분 고정, 정시/30분 시작, 1인 주 1회) |

### 힐링룸 예약 규칙

- **층수**: 4층 또는 5층
- **시간**: 30분 단위 (정시 또는 30분 시작, 예: 14:00, 14:30)
- **운영 시간**: 09:00 ~ 18:00
- **제한**: 1인당 주 1회 (월~일 기준)

## 사용 예시

Claude Code에서 다음과 같이 질문하면 됩니다:

- "앱 배포 일정 알려줘"
- "다음 달 배포 일정 검색해줘"
- "내 캘린더 목록 보여줘"
- "힐링룸 빈 시간 알려줘"
- "4층 힐링룸 14:00에 예약해줘"

## 개발자용: 직접 빌드

```bash
git clone https://github.com/socar-harrison/mcp-set.git
cd mcp-set
./gradlew shadowJar
claude mcp add google-calendar -s user -- java -jar $(pwd)/build/libs/google-calendar-mcp-all.jar
```

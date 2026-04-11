# Google Calendar MCP Server

SOCAR 앱 배포 캘린더를 Claude Code에서 조회할 수 있는 MCP 서버입니다.

## 사전 요구사항

- macOS (다른 OS는 미검증)
- [Claude Code](https://claude.ai/claude-code)

## 설치

### 1. Java 21 설치

```bash
brew install openjdk@21
sudo ln -sfn $(brew --prefix openjdk@21)/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk
```

설치 확인:

```bash
java --version
# openjdk 21.x.x 가 출력되면 성공
```

### 2. 클론 & 빌드

```bash
git clone https://github.com/socar-harrison/mcp-set.git
cd mcp-set
./gradlew shadowJar
```

### 3. Claude Code에 MCP 서버 등록

```bash
claude mcp add google-calendar -s user -- java -jar $(pwd)/build/libs/google-calendar-mcp-all.jar
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

## 사용 예시

Claude Code에서 다음과 같이 질문하면 됩니다:

- "앱 배포 일정 알려줘"
- "다음 달 배포 일정 검색해줘"
- "내 캘린더 목록 보여줘"

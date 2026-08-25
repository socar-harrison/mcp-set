# SOCAR Protocol Codegen MCP

`socar-protocol`의 특정 브랜치를 기준으로 Android API2 data class를 생성하는 로컬 MCP 서버입니다. Calendar API나 Google 인증은 사용하지 않습니다.

Codex와 Claude Code는 같은 `protocol-codegen-mcp-all.jar`를 실행합니다. AI는 자연어 요청을 MCP 도구 호출로 연결할 뿐이고, worktree 격리, 변경 분류, stash, 검증 같은 안전 규칙은 서버가 직접 수행합니다.

동일한 자연어 routing 설명을 담은 `socar-protocol-codegen` skill도 두 client에 공유합니다. skill은 도구 선택을 돕는 얇은 adapter이며, 실행 규칙의 기준은 항상 MCP 서버의 instructions와 core입니다.

## 동작 방식

`generate_protocol_data_classes` 도구는 다음 순서로 실행됩니다.

1. Android workspace와 protocol 브랜치를 검증하고 브랜치의 commit SHA를 고정합니다.
2. 현재 Android HEAD를 기준으로 격리된 Git worktree를 준비합니다.
3. `tools/api2-model/generate.kts`의 `branch` 값은 격리된 worktree에서만 임시 변경합니다.
4. `socar-protocol/main`과 요청 브랜치의 결과를 각각 생성해 비교합니다.
5. `H + (T - M)` 상태를 계산하고 동일 파일의 독립적인 텍스트 변경은 3-way merge하며, 충돌은 적용 전에 차단합니다.
6. 계산된 상태를 격리된 worktree에서 검증합니다.
7. `main`에서도 발생하는 생성 변경은 이름이 붙은 stash로 보관하고, 검증된 요청 브랜치 변경만 현재 workspace에 반영한 뒤 결과를 구조화해 반환합니다.

안전 기본값은 서버 내부에 고정되어 Codex와 Claude Code에서 동일합니다.

- 사용자 작업과 `tools/pbandk` 내부 수정사항을 덮어쓰지 않습니다.
- `generate.kts`의 임시 branch 값은 커밋하지 않습니다.
- commit, push, stash pop, stash drop을 자동 실행하지 않습니다.
- 동일 파일에서 안전하게 병합할 수 없는 변경을 발견하면 현재 workspace를 수정하기 전에 중단합니다.
- `dryRun`으로 생성·분류 결과만 미리 확인할 수 있습니다.

도구 입력은 모든 client에서 같습니다.

| 입력 | 기본값 | 설명 |
|---|---:|---|
| `protocolBranch` | 필수 | remote에 push된 `socar-protocol` 브랜치 이름 |
| `workspacePath` | 환경 또는 현재 디렉터리 | `socar-android-library` 절대 경로 |
| `dryRun` | `false` | stash 생성과 workspace 반영 없이 미리보기 |
| `verify` | `true` | 반영 전에 격리된 worktree에서 API2 model module 검증 |

## 사전 준비

- Java 21
- `ANDROID_HOME` 또는 `ANDROID_SDK_ROOT`로 접근 가능한 Android SDK
- Git, `buf`, `protoc`, Kotlin command-line runner
- `git@github.com:socar-inc/socar-protocol.git`에 대한 SSH 접근 권한
- `tools/pbandk` submodule이 초기화되어 있고 generator distribution이 빌드된 Android workspace
- 요청할 protocol 브랜치가 GitHub remote에 push된 상태

`pbandk`가 아직 준비되지 않았다면 Android workspace에서 실행합니다.

```bash
git submodule update --init tools/pbandk
cd tools/pbandk
./gradlew :protoc-gen-pbandk:protoc-gen-pbandk-jvm:installDist
```

현재 `generate.kts`의 OS 지원 범위도 그대로 적용됩니다.

## 릴리스 설치

설치 스크립트는 JDK를 내려받거나 시스템 Java를 변경하지 않습니다. 기존 Java 21을 확인하고, GitHub Release의 JAR와 SHA-256 파일을 함께 내려받아 검증한 뒤 같은 파일시스템에서 원자적으로 교체합니다. 이전 JAR는 `.previous`로 남깁니다.

공유 skill 역시 별도 release asset과 SHA-256을 검증합니다. Codex 또는 Claude가 설치된 경우 각각 `${CODEX_HOME:-~/.codex}/skills/socar-protocol-codegen/SKILL.md`와 `${CLAUDE_CONFIG_DIR:-~/.claude}/skills/socar-protocol-codegen/SKILL.md`에 설치하며, 기존 파일은 `.previous`로 보존합니다.

mutable `main`의 스크립트를 바로 실행하지 말고, 설치할 release tag를 checkout해 내용을 검토한 뒤 실행하세요.

```bash
git clone --branch v2.0.0 --depth 1 \
  https://github.com/socar-harrison/mcp-set.git
cd mcp-set
./install.sh \
  --project-dir "/absolute/path/to/socar-android-library" \
  --version v2.0.0
```

`--version`을 생략하면 최신 release를 사용합니다. 재현 가능한 팀 설치에는 release tag를 명시하세요. 다른 Java 21을 사용하려면 `JAVA_BIN=/absolute/path/to/java`를 지정할 수 있습니다.

설치 위치의 기본값은 `~/.local/share/socar-protocol-codegen-mcp/`입니다. `MCP_INSTALL_DIR`로 변경할 수 있습니다.

설치 프로그램은 다음 원칙으로 MCP client를 설정합니다.

- Claude Code CLI가 있고 현재 `--scope project` 문법을 지원하면 대상 프로젝트의 `.mcp.json`에 project scope로 등록합니다.
- Codex CLI가 있고 현재 `mcp add` 문법을 지원하면 등록합니다. 현재 Codex CLI의 `mcp add`는 user scope이므로 엄격한 project scope가 필요하면 아래 설정 template을 사용하세요.
- 같은 이름의 MCP 설정이 이미 있으면 삭제하거나 덮어쓰지 않습니다. JAR는 고정 경로에 설치되므로 기존 설정이 그 경로를 사용한다면 새 JAR가 그대로 적용됩니다.
- CLI가 없거나 예상한 명령 형식이 아니면 JAR만 설치하고 수동 명령을 출력합니다.

client 설정을 건너뛰려면 다음과 같이 실행합니다.

```bash
./install.sh --no-client-config --version v2.0.0
```

## Project scope 수동 설정

### Claude Code

Android 프로젝트 루트에서 실행합니다.

```bash
claude mcp add --scope project socar-protocol-codegen \
  -e SOCAR_ANDROID_WORKSPACE=/absolute/path/to/socar-android-library -- \
  /absolute/path/to/java -jar \
  /absolute/path/to/protocol-codegen-mcp-all.jar
```

동일한 JSON 예시는 [`config/claude.mcp.json.example`](config/claude.mcp.json.example)에 있습니다. `.mcp.json`을 공유하기 전 팀에서 실행 경로 정책을 합의하고, Claude Code가 project MCP 신뢰 여부를 물으면 내용을 검토한 뒤 승인하세요.

### Codex

[`config/codex.config.toml.example`](config/codex.config.toml.example)의 내용을 Android 프로젝트의 `.codex/config.toml`에 병합하고 절대 경로 placeholder를 교체합니다. Codex가 해당 프로젝트를 trusted project로 인식해야 project 설정을 읽습니다.

Codex CLI의 user scope 등록을 원하는 경우 다음 명령도 사용할 수 있습니다.

```bash
codex mcp add socar-protocol-codegen \
  --env SOCAR_ANDROID_WORKSPACE=/absolute/path/to/socar-android-library -- \
  /absolute/path/to/java -jar \
  /absolute/path/to/protocol-codegen-mcp-all.jar
```

두 client 모두 새 MCP 설정을 읽도록 세션을 다시 시작하세요.

## 자연어 사용 예시

명시적으로 skill 이름을 입력할 필요는 없습니다.

- `socar-protocol의 feature/ABC-123 브랜치 기준으로 API2 data class 생성해줘. 관련 없는 생성 변경은 stash 해줘.`
- `feature/ABC-123로 생성될 data class 변경을 dry run으로 먼저 보여줘.`
- `Generate API2 models from socar-protocol branch feature/ABC-123 and verify the module.`

workspace가 문맥상 명확하지 않으면 Android 프로젝트의 절대 경로도 함께 알려주세요. 자연어에서 MCP 도구를 선택하는 과정은 client와 모델에 따라 달라질 수 있지만, 도구 호출 이후의 생성·stash·검증 동작은 같은 서버가 수행합니다. MCP를 지원하지 않는 client에는 별도 adapter가 필요합니다.

## 로컬 개발

```bash
./gradlew clean test shadowJar
./run.sh
```

생성되는 실행 JAR는 `build/libs/protocol-codegen-mcp-all.jar`입니다. MCP는 STDIO를 사용하므로 protocol message 이외의 진단 로그는 stdout이 아닌 stderr로 출력해야 합니다.

tag를 push하면 release workflow가 테스트와 Shadow JAR 빌드를 수행하고 다음 release asset을 게시합니다.

- `protocol-codegen-mcp-all.jar`
- `protocol-codegen-mcp-all.jar.sha256`
- `socar-protocol-codegen.SKILL.md`
- `socar-protocol-codegen.SKILL.md.sha256`

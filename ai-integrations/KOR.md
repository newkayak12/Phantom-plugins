# AI Integrations

`dev.phantom.ai` · [English](./README.md)

프로젝트 옆에서 쓰는 Claude Code와 Codex입니다. Phantom Workspaces(`dev.phantom.workspaces`)가 필요합니다.

- **도구 창:** 오른쪽의 AI Integrations 도구 창에 Projects 탭(프로젝트 그룹 사이드바), Board 탭, 그리고 에이전트 터미널마다 하나씩 탭이 있습니다. 사이드바에서 저장소를 중첩 그룹으로 묶을 수 있습니다(새 그룹, 이름 변경, 중첩, 순서, 접기, 드래그 앤 드롭. 그룹을 자기 하위 그룹에 놓을 수는 없습니다). 저장소는 worktree를, worktree는 에이전트 세션을 보여 주고, GitHub 플러그인에 그 원격의 계정이 있으면 열린 PR 제목도 보여 줍니다.
- **세션:** New Claude Session, New Codex Session, Resume Session은 worktree에서 `claude`나 `codex`를 이 도구 창의 터미널 탭으로 띄웁니다. 세션은 작업 중, 입력 대기, 완료로 표시되고 읽지 않음 표시가 붙습니다.
- **보드:** Board 탭은 모든 worktree를 카드로 열(Todo, In progress, In review, Done)에 보여 주고, 카드를 끌어 옮깁니다. 카드에는 브랜치, 세션 상태 점, 프롬프트가 나옵니다.
- **대시보드:** Open Agent Dashboard(Find Action)가 별도 창을 엽니다. 세션을 Needs attention, Working, Done으로 나누고 마지막 프롬프트와 답, 서브에이전트, 검색, 에이전트/프로젝트 필터를 보여 줍니다. 세션을 누르면 그 터미널로 갑니다.
- **상태 표시줄:** 사용량 위젯이 Claude의 `5h`, `wk`(와 Fable), Codex의 `5h`, `wk`를 보여 주고, 초기화 시각은 툴팁에 나옵니다.
- **계정:** Settings | Tools | AI Integrations에 에이전트별로 System default와 추가한 계정이 나옵니다. 계정을 추가하면 전용 설정 폴더(`CLAUDE_CONFIG_DIR` 또는 `CODEX_HOME`)를 만들고, 거기에 훅을 설치하고, CLI 로그인을 터미널로 엽니다. 활성으로 표시한 계정이 새 터미널에 쓰입니다. 토큰은 Phantom이 저장하지 않고 CLI가 로그인을 관리합니다.

## 쓰기 전에 읽어 주세요

- **권한 확인은 항상 건너뜁니다.** 끄는 설정은 없습니다. 모든 터미널이 `claude --dangerously-skip-permissions` 또는 `codex --dangerously-bypass-approvals-and-sandbox`로 실행됩니다(이어가기: `claude --dangerously-skip-permissions --resume <id>`, `codex resume --dangerously-bypass-approvals-and-sandbox <id>`). 에이전트가 묻지 않고 worktree에서 명령을 실행하고 파일을 고칠 수 있습니다.
- **훅은 IDE를 시작할 때 에이전트 설정에 기록됩니다.** System default와 모든 계정 폴더가 대상이며, Claude Code는 `~/.claude/settings.json`(이벤트별 훅), Codex는 `<CODEX_HOME>/hooks.json`(기본 `~/.codex/hooks.json`)입니다. 직접 만든 훅과 다른 키는 그대로 두고, 심볼릭 링크 파일은 링크와 권한을 유지하며, 올바른 JSON이 아닌 파일은 건드리지 않고 경고를 보여 줍니다. 훅은 로컬 서버(임의 포트, 토큰)로 이벤트를 보낼 뿐 컴퓨터 밖으로 데이터를 보내지 않습니다. Codex는 새 훅을 검토하고 신뢰할지 직접 묻습니다. `config.toml`에는 아무것도 쓰지 않습니다.
- **Claude의 `statusLine`을 가져갑니다.** `settings.json`의 `statusLine`이 플러그인 스크립트(사용량 위젯에 데이터를 줍니다)로 바뀝니다. 이전 값은 백업하지 않으므로 Phantom 밖에서 실행한 Claude에도 적용됩니다.
- **사용량은 인증 정보를 읽습니다.** macOS에서는 Claude OAuth 토큰을 `/usr/bin/security`로 키체인에서 읽고, 없으면 설정 폴더의 `.credentials.json`을 읽습니다. Codex 사용량은 `codex app-server`와 `auth.json`에서 가져옵니다. 각 제공사의 사용량 엔드포인트로만 보냅니다.

터미널 시작이나 훅 설치가 실패하면 로그에 남기고 알림으로 보여 줍니다. `-Dphantom.ai.claudeDir` / `-Dphantom.ai.codexDir`를 지정하면(실제 IDE 프로브가 지정합니다) System default가 어디서나 그 폴더가 됩니다: 훅 설치, 실행되는 에이전트의 `CLAUDE_CONFIG_DIR` / `CODEX_HOME`, 자격 증명(그 폴더의 `.credentials.json`, 키체인은 그 폴더의 접미사 붙은 서비스 이름으로 조회), Codex 사용량, 세션 조회. 지정하지 않으면 System default는 환경 변수를 설정하지 않고 `~/.claude` / `~/.codex`를 씁니다. 프로브는 `-Dphantom.ai.agentBinDir`(호출만 기록하는 `claude`/`codex` 스텁)과 `-Dphantom.ai.keychain=false`(키체인과 `/usr/bin/security`를 읽지 않음)도 지정합니다.

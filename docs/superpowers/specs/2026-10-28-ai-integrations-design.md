# AI Integrations: 에이전트 작업공간 (Orca 대체) — Spec

날짜: 2026-10-28 · 대상: 새 플러그인 AI Integrations (`workspaces` 0.2.0, `dev.phantom.workspaces`에 의존)
참고 구현: [stablyai/orca](https://github.com/stablyai/orca) (Electron/TypeScript, 소스 읽기만 함)

## Problem

지금은 여러 저장소·worktree에서 Claude/Codex를 병렬로 돌릴 때 Orca를 따로 띄워 쓴다. 목표는 Orca를 대체하는 것이다. 프로젝트 그룹, worktree별 에이전트
상태, 칸반 보드, 계정 전환·사용량이 전부 Orca에 있고 IDE에는 없다. 사용자 요청: Orca의 자격증명, CardView, MultiAgentView,
grouping 등을 Phantom 플러그인으로 가져오기.

## Solution

새 플러그인 AI Integrations가 Phantom Workspaces(모든 프로젝트를 한 창의 탭으로) 위에 다음을 얹는다.

- **프로젝트 그룹 사이드바**: 그룹 → 저장소 → worktree → 에이전트 세션 줄. 세션 줄은 상태 아이콘(작업 중/대기/완료), 에이전트
  아이콘, 제목, 경과 시간을 보여준다.
- **Workspace 보드(카드 뷰)**: worktree 하나가 카드 하나. 사용자 정의 상태 열(Todo/In progress/In review/Done)로 드래그.
- **Agent Dashboard**: 에이전트 세션 하나가 카드 하나. 열은 확인 필요 / 작업 중 / 완료. 검색과 필터, 별도 창으로 띄우기.
- **멀티 에이전트**: IDE 터미널에서 Claude/Codex를 띄우고, hook으로 받은 이벤트로 상태를 실시간 표시. 세션 줄에서 이어하기.
- **AI 제공업체 계정**: 설정 화면에서 제공업체별 "시스템 기본값" + 추가 계정, 활성 계정 선택. Orca 설정 화면과 같은 형태.
- **사용량 표시**: Claude 5시간 / 주간 / 모델별(Fable) 사용률.

## User stories

1. 사용자로서, 저장소들을 그룹으로 묶어 사이드바에서 보고 싶다. 그래야 프로젝트가 많아도 찾기 쉽다.
2. 사용자로서, 각 저장소 아래 worktree와 그 안의 에이전트 세션을 상태와 함께 보고 싶다. 그래야 어떤 에이전트가 끝났거나
   나를 기다리는지 바로 안다.
3. 사용자로서, worktree를 카드로 보고 상태 열 사이로 드래그하고 싶다. 그래야 병렬 작업 진행 상황을 한눈에 관리한다.
4. 사용자로서, 버튼 하나로 IDE 터미널에서 `claude '--dangerously-skip-permissions'` 또는
   `codex '--dangerously-bypass-approvals-and-sandbox'`를 실행하고 싶다.
5. 사용자로서, 지난 세션 줄을 눌러 `claude '--dangerously-skip-permissions' '--resume' '<session-id>'` /
   `codex resume '--dangerously-bypass-approvals-and-sandbox' '<session-id>'`로 이어가고 싶다.
6. 사용자로서, Claude와 Codex 계정을 설정에서 추가·관리하고 활성 계정을 고르고 싶다. 아무것도 추가하지 않으면 이 기기의 기존
   로그인(시스템 기본값)을 그대로 쓴다.
7. 사용자로서, 모든 worktree의 에이전트 세션을 한 화면(Agent Dashboard)에서 확인 필요 / 작업 중 / 완료로 나눠 보고 싶다.
   그래야 나를 기다리는 에이전트부터 처리한다.
8. 사용자로서, Claude 사용률(5시간/주간/Fable)과 Codex 사용률을 IDE 안에서 보고 싶다.

## Implementation decisions

- **이름** — 플러그인 이름은 AI Integrations. (source: 사용자 "2. AI Integrations")
- **플러그인 구성** — 이 Spec의 기능은 전부 AI Integrations 한 플러그인에 넣는다. AI Integrations는 Workspaces 플러그인에
  의존하고, Workspaces는 그대로 단독 플러그인으로 남는다(id·폴더 변경 없음). 프로젝트 탭 스트립과 창 스택은 Workspaces 것을
  쓴다. (source: 사용자 "저거 다 한 플러그인으로 묶을거고", "ai integration이 workspace plugin에 종속성 가지게")
- **지원 에이전트** — 첫 버전은 Claude Code, Codex만. (source: 사용자 "claude, codex만")
- **실행 방식** — 터미널 탭을 열고 CLI를 실행한다. 권한 확인은 항상 건너뛰고, 끄는 설정은 두지 않는다.
  - Claude: 새 세션 `claude '--dangerously-skip-permissions'`, 이어하기 `claude '--dangerously-skip-permissions' '--resume' '<id>'`
  - Codex: 새 세션 `codex '--dangerously-bypass-approvals-and-sandbox'`, 이어하기
    `codex resume '--dangerously-bypass-approvals-and-sandbox' '<id>'`
  (source: 사용자 명령 예시, "codex도 yolo로", 끄는 설정 "NO"; Codex 옵션은 `codex --help` / `codex resume --help` 0.154.0 확인)
- **worktree와 workspace** — worktree는 사이드바·보드·대시보드에 줄/카드로만 있다. 사용자가 Phantom으로 열 때(확인하려고 열기)만
  따로 된 프로젝트 창, 즉 Workspaces 탭 하나가 된다; 에이전트를 띄운다고 창이 생기지 않는다. (source: 사용자 "아뇨아뇨아뇨?
  phantom으로 확인(열기) 하면"; Workspaces `ProjectTabsService`, `WindowStack`)
- **상태 수집: 에이전트 hook → 로컬 HTTP 서버** — Orca와 같은 구조. 플러그인이 127.0.0.1 임의 포트에 POST 전용 서버를 열고
  토큰 헤더로 인증; 에이전트 설정에 hook을 넣어 이벤트 JSON을 그 서버로 보낸다. 터미널을 띄울 때 포트·토큰·worktree·탭
  식별자를 환경변수로 넣어 이벤트를 세션에 매칭. (source: Orca `src/main/agent-hooks/*`, `hook-post-command.ts`,
  `spawn-pane-env.ts` 조사)
- **hook 설치** — 각 에이전트의 hook 방식을 그대로 따른다: Claude는 `~/.claude/settings.json`의 hooks, Codex는 Codex의 hook
  설정(`<CODEX_HOME>/hooks.json`)과 그 신뢰 확인 절차(`--dangerously-bypass-hook-trust`로 건너뛰지 않음). 플러그인 쪽 별도 켜기 단계는 두지
  않는다. 설치할 이벤트는 `HookEventMapper`의 목록 그대로다.
  - Claude 이벤트 10개: `SessionStart`, `UserPromptSubmit`, `PreToolUse`, `PostToolUse`, `PermissionRequest`, `Notification`, `Stop`, `StopFailure`, `SubagentStart`, `SubagentStop`.
  - Codex 이벤트 9개: `SessionStart`, `UserPromptSubmit`, `PreToolUse`, `PermissionRequest`, `PostToolUse`, `SubagentStart`, `SubagentStop`, `Stop`, `Interrupt`.
  - Codex hook 타임아웃은 5초, `Interrupt`만 3초.
  (source: 사용자 "그냥 각 AI hook 그대로 따라가고")
- **상태 모델** — `working | waiting | done` (+ 완료 후 확인 전 "unread"). Claude: `UserPromptSubmit`/`PreToolUse` → working,
  `PermissionRequest`/질문 → waiting, `Stop`/`StopFailure` → done. 세션 제목은 첫 프롬프트, 사용자가 바꾼 이름이 우선.
  - Codex 상태 매핑: `UserPromptSubmit`/`PreToolUse`/`PostToolUse` → working, `PermissionRequest` → waiting, `Stop`/`Interrupt` → done.
    `SessionStart`와 `SubagentStart`/`SubagentStop`은 상태를 바꾸지 않고(서브에이전트 목록만 갱신), `Notification`의 waiting은 Claude만 쓴다.
  (source: Orca `providers/claude-events.ts`, `agent-row-primary-text.ts`)
- **세션 ID** — hook 이벤트의 `session_id`를 worktree별로 저장해 이어하기에 쓴다; hook 이전 세션은
  `~/.claude/projects/<인코딩된 경로>/<id>.jsonl` 파일명에서 읽는다. (source: 대화 중 제안)
  hook 없는 Claude 세션의 id 규칙은 아래 "hook 없는 Claude 세션 id" 참고.
- **worktree·PR 데이터** — 번들된 Git 플러그인(git4idea)과 GitHub 플러그인을 쓴다; `git`/`gh`를 직접 부르지 않는다. worktree 줄
  이름은 사용자 이름 → PR 제목 → 브랜치 순. PR 조회는 GitHub 플러그인의 계정·API만 쓴다: 원격 URL 호스트는 대소문자를 가리지 않고
  원본 그대로 먼저 `~/.ssh/config`의 호스트 별칭(읽기만)을 풀고(별칭이 없고 점 없는 `github*`이면 github.com), 그 결과와 계정 서버 호스트
  양쪽에서 `ssh.`/`api.`/`www.` 접두를 떼어 맞춘다(GHE/Enterprise 호스트 포함), 같은 호스트에 계정이 여럿이면 별칭 접미 또는 저장소 owner와 같은 로그인을 고른다. 조회 순서는 origin → 다른 원격 →
  origin이 fork이면 upstream(parent) 저장소이고, 이때 head는 `<origin owner>:<브랜치>`. (source: Phantom 번들 확인 `BaseIdeaProperties`; Orca
  `worktree-card-title-display.ts`; 하네스 실행 E-d73bf2b2 항목 6)
- **그룹 저장** — 그룹(이름, 부모 그룹, 순서, 접힘), 저장소의 소속 그룹·순서, worktree 보드 상태·순서를 IDE 설정
  (`PersistentStateComponent`)에 저장. 저장소는 그룹 하나에만 속하고 그룹은 중첩 가능. (source: Orca `project-group-types.ts`,
  `project-groups.ts`)
- **계정** — 플러그인은 토큰을 보관하지 않는다. 계정 = 플러그인 관리 설정 폴더. 추가 시 터미널에서 해당 폴더로 CLI 로그인을
  실행(Claude `CLAUDE_CONFIG_DIR`, Codex `CODEX_HOME`); 활성 계정은 이후 새로 여는 터미널에 환경변수로 적용. "시스템 기본값"은
  환경변수를 넣지 않는다. 설정 화면은 제공업체별 섹션 + "계정 추가". (source: 사용자 스크린샷; Orca
  `claude-accounts/environment.ts`, `codex-accounts/*`)
- **Claude 사용량 출처** — statusLine으로 받는 값과 `api.anthropic.com/api/oauth/usage` 폴링을 둘 다 쓴다. 인증은 사용자에게
  묻지 않고 CLI가 이미 저장한 로그인을 읽는다; 추가 로그인 화면을 띄우지 않는다. macOS 키체인은 IDE 프로세스에서 직접(Java
  키체인 API) 읽지 않고 `/usr/bin/security find-generic-password`로 읽는다: Claude CLI가 만든 `Claude Code-credentials*` 항목은
  `com.apple.security`(apple-tool)만 허용 목록에 있어 이 경로는 허용 창이 뜨지 않는다. IDE가 직접 읽으면 Phantom은 Team ID가
  없어 빌드(cdhash)가 바뀔 때마다 허용 창이 다시 뜬다. 키체인에 없으면 `<configDir>/.credentials.json`.
  (source: 사용자 "다 쓰고(인증 안귀찮게하고)", "IDEA에서 자꾸 키체인 물어보는거"; 로그인 키체인 ACL 확인 2026-10-28;
  Orca `rate-limits/claude-oauth-credentials.ts`, `macos-keychain/generic-password.ts`)
- **statusLine** — 목적이 Orca 대체이므로 Claude의 statusLine은 이 플러그인 것으로 둔다(Orca와 동시 사용은 고려하지 않음).
  (source: 사용자 "orca 대체 목적")
- **Codex 사용량** — 첫 버전에 넣는다. Orca는 `codex app-server`의 `account/rateLimits/read`와
  `chatgpt.com/backend-api/wham/usage`를 쓴다. `codex app-server`는 짧게 사는 읽기 전용 프로브로 띄운다(아래 "Codex 프로브"). (source: 사용자 "8 네"; Orca `rate-limits/codex-*` 조사)
- **세션 제목 채우기** — 세션 항목은 (worktree, 제공업체, 세션 id)당 하나이고, 저장된 제목(`firstPrompt`)은 프롬프트가 실린 첫 이벤트에서
  채운다(`SessionStart`처럼 프롬프트 없는 이벤트는 건너뜀). 이미 있으면 뒤의 프롬프트로 덮어쓰지 않고, 비어 있던 `accountId`만 채운다. 제목은
  IDE 설정에 저장돼 재시작 뒤에도 남는다. (source: 하네스 실행 E-d73bf2b2 항목 1; `AiState.rememberSession`)
- **hook 없는 Claude 세션 id** — hook이 없으면 세션 시작 이후(2초 여유) 만들어지고 수정된 `.jsonl` 중, 같은 worktree의 다른 세션과 저장된
  id가 쓰지 않은 것 가운데 가장 먼저 만들어진 파일을 그 세션 id로 묶는다. 한 번 묶으면 바뀌지 않는다. hook id가 오면 그것이 우선. 열린 탭은
  3초마다, 탭을 닫을 때는 열린 형제 탭까지 시작 순서대로 묶는다. `--session-id`는 쓰지 않는다. 한계: Claude는 대화에서 출력이 나올 때 파일을
  만들므로 hook 없는 두 탭 중 나중 탭에 먼저 입력하면 둘의 id가 서로 바뀌어 묶인다(id는 서로 다름). 둘을 가를 신호가 없어 고치지 않고 한계로
  둔다. (source: 하네스 실행 E-d73bf2b2 항목 5; 사용자 "--session-id 쓰지 않기")
- **Codex 프로브** — 사용량용 `codex app-server`는 Orca의 짧게 사는 인자로 띄운다: `codex -c approval_policy=never -c features.plugins=false
  -s read-only -a never app-server`. 작업 디렉터리는 IDE 시스템 경로 아래 숨김 `phantom-ai/codex-probe-cwd`(IDE 밖이면 임시 폴더의
  `phantom-ai-codex-probe-cwd`)이고 `user.dir`을 쓰지 않는다. 루트 같은 경로나 디렉터리가 아닌 경로는 거부. `CODEX_HOME`은 계정 폴더가 있을 때만
  넣는다. (source: 하네스 실행 E-d73bf2b2 항목 2; Orca 짧게 사는 Codex 프로브)
- **취소와 hook 설치 실패** — 이 플러그인(`dev.phantom.ai`)의 `runCatching`은 모두 `core/BestEffort.kt`의 `bestEffort`로 바꿨고(JSON·UUID·
  Instant 파싱, 프로세스 닫기 포함), 취소를 볼 수 있는 `catch (Exception|Throwable|RuntimeException)`은 `rethrowIfCancellation` 또는 먼저
  던지는 취소 처리로 `ProcessCanceledException`/`CancellationException`을 삼키지 않고 다시 던진다(그 외 예외만 실패 결과). 터미널을 열다
  취소되면 세션 등록도 지우고 던진다. 예외로 취소와 무관한 것은 `IOException`/`InterruptedException`만 잡는 catch와 `Disposer` 정리 후 다시
  던지는 `catch (Throwable)`뿐이다. `HookJson.read`는 파싱 실패를 `bestEffort`로 null(건드리지 않음)로 돌려준다. 계정을 추가할 때 hook 설치가 false를
  돌려주거나 던져도 계정은 만들고 로그인도 실행하며, 'AI Integrations' 그룹의 ERROR 알림을 정확히 한 번, 제공업체와 폴더 이름을 담아 띄운다.
  설치 중 취소는 그대로 전파(알림·로그인 없음). (source: 하네스 실행 E-d73bf2b2 항목 3·4)
- **실제 IDE 프로브 샌드박스** — `runIdeProbe`는 사용자 홈을 건드리지 않는다. `-Dphantom.ai.claudeDir`/`-Dphantom.ai.codexDir`로 System 기본
  폴더(hook·`CLAUDE_CONFIG_DIR`/`CODEX_HOME`·자격증명·세션)를 `build/realide-agent-homes`로 돌리고, `-Dphantom.ai.agentBinDir`의
  `bin/claude`·`bin/codex` 스텁이 호출(자기 경로, 인자, 설정 폴더 환경변수)을 `calls.log`에 남기고 대기하며, `-Dphantom.ai.keychain=false`로
  키체인과 `/usr/bin/security`를 읽지 않는다. 시나리오는 스텁이 `calls.log`에 쓸 때까지 기다려 터미널 실행을 확인한다.
  (source: 하네스 실행 E-d73bf2b2 항목 2·8)
- **Agent Dashboard** — Orca Agent Dashboard와 같은 형태. (source: 사용자 스크린샷 "이거 도"; Orca `AgentDashboardDrawer.tsx`,
  `dashboard-popout/`, `shared/dashboard-snapshot.ts` 조사)
  - 열: 확인 필요(waiting) / 작업 중(working) / 완료(done). 열마다 개수, 비면 "없음".
  - 카드: 에이전트 아이콘, 세션 제목, 상태 점, 마지막 사용자 프롬프트, 마지막 에이전트 답변 요약, 접을 수 있는 서브에이전트 목록,
    프로젝트 아이콘 + 브랜치 + PR 번호 배지, 경과 시간. 클릭하면 그 세션의 터미널로 이동.
  - 상단: 총 개수, 워크트리·프로젝트·에이전트 검색, 필터.
  - 데이터는 hook 이벤트(프롬프트, 답변, 서브에이전트 이벤트)에서 온다; 사이드바 세션 줄과 같은 상태 모델을 쓴다.
- **에이전트 터미널 위치** — 에이전트 터미널은 아래 Terminal tool window가 아니라 AI Integrations tool window 안에 뜬다. 이 tool
  window는 오른쪽 사이드에 두거나 플로팅으로 띄울 수 있다(사이드바 아이콘이 있는 그 자리). worktree를 Phantom으로 열었는지와
  상관없다. (source: 사용자 "터미널... 오른쪽 사이드 혹은 플로팅, 그니까 그 아이콘이 있는 위치", "wt 여는거랑 workspace랑 별개")
- **UI 위치** — 사이드바와 보드는 tool window(보드는 같은 tool window의 탭), Agent Dashboard는 별도 창, 계정은 Settings 페이지,
  사용량은 상태바.
  (source: 대화 중 제안)

## Testing decisions

- Seam 1 (순수 로직, 단위 테스트): hook 이벤트 → 상태 매핑, 실행 명령 조립(새 세션/이어하기, 계정 환경변수), 그룹 정렬·중첩
  정규화, 보드 열 배치·순서, 세션 jsonl 파일명 파싱. — prior art: `ProjectTabsTest`, `WindowStackTest`(플랫폼 없이 로직만)
- Seam 2 (IDE 안 통합 테스트): hook 서버에 실제 HTTP POST → 세션 상태 변경 이벤트, 잘못된 토큰 403, worktree 프로젝트가
  탭 스트립에 들어감. — prior art: `WorkspacesInIdeTest`
- Seam 3 (실제 IDE): AI Integrations에 Workspaces와 같은 `runIdeProbe` 태스크를 두고 시나리오 실행 시나리오로 사이드바·보드 드래그,
  터미널 실행 확인. — prior art: `realide-probe`의 Workspaces/Tabs 시나리오
- 꼭 고정할 경우: 같은 worktree에 세션 여러 개; 이벤트가 오지 않는 세션(hook 미설치); 이어하기 ID가 없는 세션; 계정 없음 =
  시스템 기본값(환경변수 없음); AI Integrations를 꺼도 Workspaces(0.2.0) 동작이 그대로인지.

## Out of scope

- OpenCode, Devin, Cursor, Gemini 등 Claude/Codex 외 에이전트 — 첫 버전 이후. (source: 사용자 "claude, codex만")
- Orca의 structured(네이티브 채팅) 실행 모드, 모바일 푸시, SSH/WSL 경로 — 터미널 실행만 하기로 함.
- Tab Groups(`tabs`) 합치기 — 따로 둔다. (source: 사용자 "1 따로 두고")
- Phantom 본체 번들에 VSCode 키맵 추가 — 별건, 하지 않기로 함. (source: 사용자 "그럼 하지 마세영")

## Open questions

없음.

stories 8 · decisions 23 · open 0

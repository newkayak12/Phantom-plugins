# Phantom AI Integration 변경 이력

## 미배포

## 0.7.3 (2026-11-28)

- 에이전트 세션을 시작할 때 고스트 모드가 꺼져 있으면 IDE 실행마다 한 번 켜기를 제안한다. "Don't Ask Again"을 고르면 다시 묻지 않고, 고스트 모드가 없는 Phantom에서는 제안하지 않는다. (#PR)

## 0.7.2 (2026-11-27)

- 연결된 worktree의 Rename이 Phantom Workspaces를 거쳐 폴더 이름을 바꾸고, Projects and Files에서 바꾼 이름도 트리에 나온다. 에이전트 세션이 있거나 프로젝트로 열린 worktree는 거절한다. (#179)
- worktree 트리에 사용자 폴더를 둔다: New Folder…, 폴더 이름 바꾸기와 지우기(안의 worktree는 맨 위로 돌아감), worktree를 끌어 넣고 빼기. 저장소별로 저장되고 디스크에는 아무것도 바뀌지 않는다. (#179)

## 0.7.1 (2026-11-27)

- 트리와 보드의 열린 PR 제목은 Settings | Tools | Phantom AI Integration의 "Show the open pull request of each branch"를 켠 경우에만 GitHub API로 찾는다. 기본은 꺼져 있다. (#167)
- 에이전트 팀 확인은 Claude 세션이 모두 끝나면 멈추고 다음 세션에서 다시 시작한다. (#167)
- GitHub 토큰 조회와 에이전트 데이터베이스 도구가 취소 요청에 응답한다. (#167)

## 0.7.0 (2026-11-27)

- Phantom Local Shared Index가 필요하다. 에이전트의 `find_symbol`, `find_usages`, `file_outline`, `get_file_problems`는 이름과 인자가 그대로이고 Local Shared Index가 답한다. 에이전트가 `search_text`로 IDE의 Find in Files와 인덱스를 써서 텍스트를 찾고, 일치한 줄과 앞뒤 몇 줄을 받는다. (#162)

## 0.6.0 (2026-11-24)

- 도구 창 스트라이프 아이콘을 말풍선 목록 대신 반짝임이 붙은 터미널 그림으로 바꿨다. 16px와 20px용을 따로 그렸다. (#112)
- worktree 트리는 30초마다 `git worktree list`와 PR 조회를 다시 하지 않는다. 경과 시간만 갱신하고, 전체 갱신은 세션이나 상태가 바뀔 때와 IDE가 다시 활성화될 때 한다. (#117)
- IDE에 모달 대화 상자가 열려 있어도 원격 제어가 멈추지 않는다. 목록 조회는 그대로 답하고, 에이전트 시작과 재개는 30초 안에 시작하지 못하면 실패로 답한다. (#117)
- 오래 걸리는 MCP 도구 호출(DB 쿼리, 동의 대화 상자)이 있어도 훅과 status line이 기다리지 않는다. MCP 요청은 자체 스레드에서 처리한다. (#117)
- 프로젝트 창을 다시 열 때 분리된 에이전트를 다시 붙이면서 tmux 확인을 EDT 밖에서 한다. 창이 열리는 동안 몇 초씩 멈추지 않는다. (#117)
- 사용량 위젯은 프로젝트를 열 때 `codex app-server`를 실행하지 않는다. Codex 사용량은 Codex 세션이 생기거나 위젯을 클릭한 뒤에만, 그 뒤로는 Codex 세션이 실행 중일 때만 묻고, `codex`가 없으면 묻지 않는다. (#117)
- 새 worktree(New worktree나 보드의 In progress)를 새 창의 프로젝트로 연다. New Worktree 대화 상자의 체크박스로 끌 수 있고 선택은 기억된다. 에이전트 세션은 원래 창에 남는다. (#113)
- 새 worktree를 도구 창 제목 표시줄의 +와 마우스를 올린 저장소/worktree 행 끝의 +로 만든다. 전에는 행의 오른쪽 클릭 메뉴에만 있었다. (#113)
- worktree와 세션의 이름을 F2, 도구 창 제목 표시줄의 Rename, 행 메뉴의 Rename으로 바꾼다. 세션 이름은 저장돼 다시 열어도 남는다. 행 메뉴는 Shift+F10이나 메뉴 키로도 열린다. (#113)
- 보드의 열이 작업 단계가 됐다. New Card로 작업을 계획하고, In progress로 옮기면 worktree를 만들고 카드의 프롬프트로 에이전트를 시작한다. 에이전트가 끝나면 카드가 In review로 가고, 되돌리면 후속 프롬프트를 같은 세션에 보낸다(바쁘면 대기). Done은 확인 후 worktree를 지울 수 있고 병합이나 푸시는 하지 않는다. 카드에 세션 상태 배지, diff(Changes), PR 링크가 붙는다. (#113)
- Agent Dashboard 창은 상태별 열 대신 열려 있는 모든 프로젝트의 보드를 프로젝트 필터와 함께 보여 준다. 검색, 에이전트 필터, 서브에이전트 목록은 빠졌다. (#113)

## 0.5.2

- 계정의 로그인과 이메일을 CLI에 묻는다. Claude Code는 `claude auth status --json`, Codex는 `codex app-server`의 `account/read`다. Codex의 `auth.json`(토큰이 든 파일)과 Claude의 `.claude.json` 로그인 정보를 더는 읽지 않는다.
- 설정 페이지가 열릴 때 계정마다 CLI에 로그인 상태를 묻는다(진행 표시와 함께). 그 사이에는 마지막 답을 쓴다.
- README의 인증 정보 설명을 실제 동작에 맞췄다.

## 0.5.1 (2026-11-22)

- macOS 시스템 터미널(Terminal)은 Apple 이벤트 대신 `open -a Terminal`로 연다. 자동화 권한을 묻지 않고, 권한이 없을 때 창이 안 뜨던 문제가 없다.

## 0.5.0 (2026-11-22)

- 에이전트 터미널을 IDE 탭 대신 iTerm이나 시스템 터미널에서 열 수 있다(Settings | Tools | Phantom AI Integration | Terminal). 기본값 Automatic은 tmux가 있으면 IDE 탭, 없으면 시스템 터미널이다. tmux가 있으면 바깥 터미널은 분리된 tmux 세션에 붙기만 해서, 창을 닫아도 에이전트가 남고 IDE 탭에서 다시 연다. 다른 플러그인이 터미널을 더하는 확장 지점 `dev.phantom.ai.terminalBackend`를 열었다.

## 0.4.1 (2026-11-22)

- 에이전트가 Database Console 데이터 소스를 처음 읽을 때 그 데이터 소스와 결과가 가는 곳(Claude Code는 Anthropic, Codex는 OpenAI)을 보여 주고 허용을 받는다. 데이터 소스마다 한 번 묻고, 거부하면 에이전트는 아무것도 받지 못한다. 데이터베이스 도구를 끄면 허용 기록을 지운다. (#77)
- 원격 제어는 이 컴퓨터(127.0.0.1)와 Tailscale 주소에서만 바로 켜진다. 로컬 네트워크·공용 주소는 평문 HTTP라서 "Serve unencrypted on this address anyway"를 직접 체크해야 서버가 열린다. (#77)

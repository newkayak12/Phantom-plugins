# Phantom AI Integration 변경 이력

## 미배포

- 보드의 열이 작업 단계가 됐다. New Card로 작업을 계획하고, In progress로 옮기면 worktree를 만들고 카드의 프롬프트로 에이전트를 시작한다. 에이전트가 끝나면 카드가 In review로 가고, 되돌리면 후속 프롬프트를 같은 세션에 보낸다(바쁘면 대기). Done은 확인 후 worktree를 지울 수 있고 병합이나 푸시는 하지 않는다. 카드에 세션 상태 배지, diff(Changes), PR 링크가 붙는다.
- Agent Dashboard 창은 상태별 열 대신 열려 있는 모든 프로젝트의 보드를 프로젝트 필터와 함께 보여 준다. 검색, 에이전트 필터, 서브에이전트 목록은 빠졌다.

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

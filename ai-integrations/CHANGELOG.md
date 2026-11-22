# Phantom AI Integration 변경 이력

## 미배포

## 0.5.1 (2026-11-22)

- macOS 시스템 터미널(Terminal)은 Apple 이벤트 대신 `open -a Terminal`로 연다. 자동화 권한을 묻지 않고, 권한이 없을 때 창이 안 뜨던 문제가 없다.

## 0.5.0 (2026-11-22)

- 에이전트 터미널을 IDE 탭 대신 iTerm이나 시스템 터미널에서 열 수 있다(Settings | Tools | Phantom AI Integration | Terminal). 기본값 Automatic은 tmux가 있으면 IDE 탭, 없으면 시스템 터미널이다. tmux가 있으면 바깥 터미널은 분리된 tmux 세션에 붙기만 해서, 창을 닫아도 에이전트가 남고 IDE 탭에서 다시 연다. 다른 플러그인이 터미널을 더하는 확장 지점 `dev.phantom.ai.terminalBackend`를 열었다.

## 0.4.1 (2026-11-22)

- 에이전트가 Database Console 데이터 소스를 처음 읽을 때 그 데이터 소스와 결과가 가는 곳(Claude Code는 Anthropic, Codex는 OpenAI)을 보여 주고 허용을 받는다. 데이터 소스마다 한 번 묻고, 거부하면 에이전트는 아무것도 받지 못한다. 데이터베이스 도구를 끄면 허용 기록을 지운다. (#77)
- 원격 제어는 이 컴퓨터(127.0.0.1)와 Tailscale 주소에서만 바로 켜진다. 로컬 네트워크·공용 주소는 평문 HTTP라서 "Serve unencrypted on this address anyway"를 직접 체크해야 서버가 열린다. (#77)

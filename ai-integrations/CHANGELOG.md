# Phantom AI Integration 변경 이력

## 미배포

- 에이전트가 Database Console 데이터 소스를 처음 읽을 때 그 데이터 소스와 결과가 가는 곳(Claude Code는 Anthropic, Codex는 OpenAI)을 보여 주고 허용을 받는다. 데이터 소스마다 한 번 묻고, 거부하면 에이전트는 아무것도 받지 못한다. 데이터베이스 도구를 끄면 허용 기록을 지운다. (#74)
- 원격 제어는 이 컴퓨터(127.0.0.1)와 Tailscale 주소에서만 바로 켜진다. 로컬 네트워크·공용 주소는 평문 HTTP라서 "Serve unencrypted on this address anyway"를 직접 체크해야 서버가 열린다. (#74)

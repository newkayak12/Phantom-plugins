# Support for Docker 변경 이력

## 미배포

## 0.6.4 (2026-11-27)

- `ARG A=1 B=2`처럼 한 줄에 인자 여러 개를 선언해도 오류로 표시하지 않는다. (#171)
- `RUN echo "<<EOF"`처럼 따옴표 안의 `<<`를 heredoc 시작으로 읽지 않는다. (#171)
- Containers 창의 새로 고침이 겹쳐 `docker ps`가 두 줄로 돌던 문제를 고쳤다. 한 번에 하나만 돌고, 실패가 이어지면 간격을 최대 1분까지 늘린다. (#171)
- Compose 볼륨의 `C:\data:/data` 같은 Windows 경로를 볼륨 이름 `C`로 보지 않는다. (#171)
- Compose 검사와 이동이 이웃 compose 파일을 프로젝트의 아무 편집마다 다시 읽지 않고, Dockerfile의 COPY 검사가 빌드 컨텍스트를 매번 다시 찾지 않는다. (#171)
- Containers 창과 Docker Compose 실행 구성의 문구를 메시지 번들로 옮겼다. (#171)

## 0.6.3 (2026-11-26)

- 표시 이름을 바꿨다: Phantom for Docker → Support for Docker. 플러그인 id와 기능은 그대로다. (#136)
- Containers 창은 IDE가 다른 앱 뒤에 있는 동안 `docker ps -a`를 3초마다 돌리지 않고 멈췄다가, IDE로 돌아오면 바로 새로 고친다. (#114)
- Docker 도구 창 아이콘을 빈 사각형 대신 쌓인 컨테이너 세 개로 바꿨다. (#112)

## 0.6.2 (2026-11-22)

- 이름을 바꿨다: Docker Support → Phantom for Docker. Phantom으로 시작하고, 지원하는 프로젝트 이름은 뒤에 둔다. 플러그인 id와 기능은 그대로다.

## 0.6.1 (2026-11-20)

- 플러그인 jar에 `LICENSE`, `NOTICE`와 서드파티 라이선스 고지를 넣었다. (#45)

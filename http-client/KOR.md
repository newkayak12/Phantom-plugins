# Phantom HTTP Client

`dev.phantom.http` · [English](./README.md)

`.http`, `.rest` 파일에 HTTP 요청을 쓰고 에디터에서 바로 실행합니다.

- **요청:** 메서드, URL, 헤더, 본문을 `###`로 나눠 씁니다. 요청마다 ▶ 거터, Run All, Ctrl+Enter로 실행하고, 응답 창에 상태·헤더·정리된 본문이 나옵니다. 1 MB를 넘는 본문은 일반 텍스트로 보여 줍니다. `# @timeout`이 없는 요청은 60초 안에 응답이 없으면 실패합니다.
- **변수:** `@name = value` 줄, `http-client.env.json` / `http-client.private.env.json` 환경, 응답 핸들러 스크립트에서 정한 값을 `{{name}}`으로 씁니다.
- **재사용:** `import other.http`로 그 파일의 요청과 변수를 가져오고, `run #이름`, `run other.http`, `run #이름 (@var=value)`로 실행합니다.
- **검사:** 깨진 JSON 본문, Java 클라이언트가 보내지 않는 헤더(Content-Length, Host 등), 알 수 없는 메서드, URL 안의 공백, `###` 없이 다음 요청으로 이어지는 본문, 깨진 `{{ }}`, 잘못되거나 알 수 없는 헤더, 원격 호스트로 가는 `http://`, 중복 import, 없는 `run` 이름과 파일.

2026-10-18까지는 Phantom 본체에 들어 있었습니다. 이제 플러그인이라 Phantom을 다시 빌드하지 않고 업데이트됩니다.

아직 없음: 인증 설정, gRPC·WebSocket 요청.

## 고스트 모드

Phantom의 고스트 모드(레지스트리 키 `phantom.ghost`)가 켜져 있으면 `.http` 파일과 인증 설정 검사가 꺼진다. 색칠, 완성, 요청 실행 아이콘은 그대로다.

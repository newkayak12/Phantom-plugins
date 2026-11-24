# Support for OpenAPI 변경 이력

## 미배포

- 표시 이름을 바꿨다: Phantom for OpenAPI → Support for OpenAPI. 플러그인 id와 기능은 그대로다. (#?)
- 핸들러 메서드의 스펙 거터가 엔드포인트 목록을 YAML·JSON 입력마다 다시 찾지 않는다. 각 엔드포인트 제공자의 변경 추적을 따르고, 애너테이션이 없는 파일에서는 제공자를 묻지 않는다. (#119)

## 0.2.3 (2026-11-22)

- 이름을 바꿨다: OpenAPI Support → Phantom for OpenAPI. Phantom으로 시작하고, 지원하는 프로젝트 이름은 뒤에 둔다. 플러그인 id와 기능은 그대로다.

## 0.2.2

- 입력 중 CPU를 덜 쓴다. Java나 Kotlin을 고쳐도 프로젝트의 YAML/JSON 파일을 다시 훑지 않는다. 결과는 그대로다.

## 0.2.1 (2026-11-20)

- 플러그인 jar에 `LICENSE`, `NOTICE`와 서드파티 라이선스 고지를 넣었다. (#45)

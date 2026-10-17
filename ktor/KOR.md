# Phantom Ktor

`dev.phantom.ktor` · [English](./README.md)

Ktor 서버 지원입니다.

- **Endpoints:** Ktor 라우트를 Phantom의 Endpoints 툴 윈도우에 Spring MVC 핸들러와 함께 보여 줍니다. 예: `GET /api/v1/menus/{id}`
  - 라우팅 DSL의 `get`, `post`, `put`, `delete`, `patch`, `head`, `options`를 중첩된 `route("…") { }` 블록까지 따라가며 읽습니다.
  - `fun Route.userRoutes()` 같은 확장 함수 안의 라우트는 그 함수를 부르는 모든 곳의 접두사를 붙여 보여 줍니다.
  - 상수로 만든 경로도 실제 값으로 풀어 줍니다.
  - Enter나 더블클릭으로 라우트 코드를 엽니다.

프로젝트가 `ktor-server-core`에 의존하면 Phantom이 이 플러그인 설치를 제안합니다. 다른 프로젝트에서는 아무 동작도 하지 않습니다.

아직 없는 것: 타입 안전 라우트(`get<Users>`), New Project 마법사(start.ktor.io), `application.conf` 자동완성.

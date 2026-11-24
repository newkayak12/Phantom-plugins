# Phantom Endpoints

`dev.phantom.endpoints` · [English](./README.md)

Endpoints 도구 창: 설치된 플러그인(Support for Spring, Support for Ktor 등)이 `com.intellij.microservices.endpointsProvider`로 알려 주는
HTTP 엔드포인트를 모듈별로 모아 보여 주고, 메서드·경로·핸들러로 걸러 볼 수 있습니다.

- Enter나 더블클릭으로 핸들러를 엽니다. 컨텍스트 메뉴에는 Go to Handler와 Copy URL이 있습니다.
- Generate HTTP Request는 엔드포인트 요청을 스크래치 파일 `generated-requests.http`에 덧붙이고 그 위치를 엽니다. 경로 변수는
  `{{변수}}`가 되고, 호스트는 모듈의 `server.port`, `server.servlet.context-path`, `spring.webflux.base-path`,
  `server.ssl.enabled`나 Ktor의 `ktor.deployment.port`에서, 없으면 `http://localhost:8080`을 씁니다.
- 목록은 창이 보일 때만 백그라운드에서 만들고, 코드가 바뀌면 다시 만듭니다. 엔드포인트 제공자가 없는 프로젝트에는 창이 생기지 않습니다.

Phantom이 이 플러그인을 기본으로 포함하고(예전에는 IDE 본체에 있던 기능), 업데이트는 마켓플레이스로 받습니다.

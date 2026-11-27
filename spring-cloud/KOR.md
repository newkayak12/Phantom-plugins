# Phantom for Spring Cloud
`dev.phantom.spring.cloud` · [English](./README.md)

Spring Cloud를 쓰는 프로젝트에서만, 오프라인으로 동작합니다.

- **Feign 클라이언트 ↔ 컨트롤러:** `@FeignClient` 인터페이스 메서드의 거터 아이콘에서 같은 프로젝트 안의, 그 요청을 받는
  컨트롤러 메서드로 갑니다. 컨트롤러 메서드의 아이콘에서는 그 엔드포인트를 부르는 Feign 클라이언트 메서드로 돌아갑니다.
  요청은 HTTP 메서드(`@GetMapping`, `@PostMapping`, ..., `@RequestMapping(method = ...)`)와 경로로 맞춥니다. 클라이언트의
  `path` 속성과 메서드 매핑을 컨트롤러의 `@RequestMapping`과 메서드 매핑에 맞춥니다. 경로 변수는 위치로 맞추고
  (`{id}`와 `{userId:\d+}`), 상수는 값을 계산하며, 컨트롤러가 구현한 인터페이스에 선언된 매핑도 봅니다.

지금은 Java만 지원합니다. 클라이언트의 `name`과 `url`은 비교하지 않으므로, 한 프로젝트에 같은 경로를 가진 서비스가 둘이면
둘 다 맞습니다. 실행 중인 서비스 레지스트리에는 접속하지 않습니다.

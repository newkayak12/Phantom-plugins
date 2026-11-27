# Phantom for Spring Security
`dev.phantom.spring.security` · [English](./README.md)

애플리케이션을 실행하지 않고 Java 소스에서 Spring Security 필터 체인을 읽습니다.

- **필터 체인 거터:** `SecurityFilterChain`을 반환하는 `@Bean` 메서드마다 자물쇠 아이콘이 붙습니다. 툴팁에는 프로젝트의 체인 가운데 몇 번째인지(`@Order` 순, 순서가 없는 체인은 마지막), `securityMatcher` 경로 또는 "모든 요청", `authorizeHttpRequests` 규칙이 순서대로 나옵니다. 예: `POST /admin/** → hasRole("ADMIN")`.
- **`anyRequest()` 뒤의 요청 매처:** `anyRequest()` 뒤에 쓴 `requestMatchers(...)`를 오류로 표시합니다. Spring Security는 시작할 때 이 설정을 거부합니다.
- **가려진 필터 체인:** `securityMatcher`가 없는 체인보다 뒤 순서인 체인을 오류로 표시합니다. 매처가 없는 체인은 모든 요청을 처리하므로 Spring Security가 시작할 때 뒤의 체인을 거부합니다. 순서 값이 같은 체인은 빈 등록 순서에 따라 달라지므로 알리지 않습니다.

체인은 빈 메서드 안의 `securityMatcher(...)`와 `authorizeHttpRequests`에 넘긴 람다에서 읽습니다. 다른 메서드에서 만든 빌더와 Kotlin DSL은 아직 읽지 않습니다.

`requestMatchers` 경로와 `@PreAuthorize` 식 자동완성은 [Support for Spring](../spring/KOR.md)에 있습니다.

프로젝트가 `spring-security-web`이나 `spring-boot-starter-security`를 쓰면 Phantom이 이 플러그인을 추천합니다. Spring Security가 없는 모듈에서는 아무 일도 하지 않습니다. 네트워크 요청과 시작 작업이 없습니다.

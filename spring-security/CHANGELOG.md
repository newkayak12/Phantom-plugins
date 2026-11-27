# Support for Spring Security 변경 이력

## 미배포

## 0.1.1 (2026-11-28)

- 표시 이름을 바꿨다: Phantom for Spring Security → Support for Spring Security. 플러그인 id와 기능은 그대로다. (#PR)

## 0.1.0 (2026-11-27)

- 첫 버전: `SecurityFilterChain` 빈 거터에 체인 순서, `securityMatcher`, `authorizeHttpRequests` 규칙을 보여 주고, `anyRequest()` 뒤의 `requestMatchers`와 모든 요청을 받는 체인 뒤에 놓인 체인을 오류로 알린다. (#192)

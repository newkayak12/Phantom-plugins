# Phantom for Spring Security 변경 이력

## 미배포

## 0.1.0 (2026-11-27)

- 첫 버전: `SecurityFilterChain` 빈 거터에 체인 순서, `securityMatcher`, `authorizeHttpRequests` 규칙을 보여 주고, `anyRequest()` 뒤의 `requestMatchers`와 모든 요청을 받는 체인 뒤에 놓인 체인을 오류로 알린다. (#192)

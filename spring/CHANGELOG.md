# Phantom for Spring 변경 이력

## 미배포

- Spring MVC 엔드포인트 목록, AOP 어드바이스 거터, `@Autowired` 빈 밖 검사와 자동 주입 검사가 메서드 본문이나 다른 언어 파일을 입력할 때마다 프로젝트 전체를 다시 찾지 않는다. 선언이 바뀔 때만 다시 찾는다. (#119)
- IntelliJ IDEA Ultimate가 만든 프로젝트를 열 때 "Unknown facet type: 'web'" 오류 알림이 더 이상 뜨지 않는다. .iml의 Web facet 설정은 그대로 남아 Ultimate에서도 읽힌다. (#111)

## 0.9.1 (2026-11-20)

- 플러그인 jar에 `LICENSE`, `NOTICE`와 서드파티 라이선스 고지를 넣었다. (#45)

# Support for JPA 변경 이력

## 미배포

## 0.7.4 (2026-11-26)

- 표시 이름을 바꿨다: Phantom for JPA → Support for JPA. 플러그인 id와 기능은 그대로다. (#136)
- Entity Diagram 도구 창 아이콘을 Database와 같던 표 아이콘에서 선으로 이어진 엔티티 상자 두 개로 바꿨다. (#112)
- `@Converter(autoApply = true)` 타입 목록과 Spring Data JDBC 애그리거트 루트·프로젝트 판별이 SQL·YAML·XML 입력마다 다시 계산되지 않는다. JVM 코드나 라이브러리가 바뀔 때만 다시 계산한다. (#119)
- IntelliJ IDEA Ultimate가 만든 프로젝트를 열 때 "Unknown facet type: 'jpa'" 오류 알림이 더 이상 뜨지 않는다. .iml의 JPA facet 설정은 그대로 남아 Ultimate에서도 읽힌다. (#111)

## 0.7.3 (2026-11-22)

- 이름을 바꿨다: JPA Support → Phantom for JPA. Phantom으로 시작하고, 지원하는 프로젝트 이름은 뒤에 둔다. 플러그인 id와 기능은 그대로다.

## 0.7.2 (2026-11-22)

- 입력 중 CPU 사용이 줄었다. 엔티티 속성의 리포지토리 사용처 거터가 리포지토리를 엔티티마다 찾지 않고 Java, Kotlin, Groovy 코드가 바뀔 때 한 번만 찾는다. SQL, YAML 등 다른 파일을 고쳐도 다시 찾지 않는다. 결과는 그대로이고, 거터가 조금 늦게 나타날 수 있다.

## 0.7.1 (2026-11-20)

- 플러그인 jar에 `LICENSE`, `NOTICE`와 서드파티 라이선스 고지를 넣었다. (#45)

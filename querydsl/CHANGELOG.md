# Support for QueryDSL 변경 이력

## 미배포

## 0.1.4 (2026-11-27)

- Q클래스가 오래됨 검사가 Kotlin 엔티티도 본다. QueryDSL이 있는 모듈의 엔티티·임베더블·매핑된 상위 클래스·`@QueryEntity` 클래스만 검사한다. (#177)
- 중첩 클래스 `Outer.Inner`의 Q클래스 `QOuter_Inner`를 찾고, `isActive()`로 읽는 boolean 속성의 경로를 사라진 것으로 알리지 않는다. (#177)
- Find Usages가 Q클래스 필드를 찾을 때만 읽기 액션을 잡고, Q클래스 캐시가 SQL·YAML·XML 입력마다 다시 계산되지 않는다. (#177)

## 0.1.3 (2026-11-26)

- 표시 이름을 바꿨다: Phantom for QueryDSL → Support for QueryDSL. 플러그인 id와 기능은 그대로다. (#136)

## 0.1.2 (2026-11-22)

- 이름을 바꿨다: QueryDSL Support → Phantom for QueryDSL. Phantom으로 시작하고, 지원하는 프로젝트 이름은 뒤에 둔다. 플러그인 id와 기능은 그대로다.

## 0.1.1 (2026-11-20)

- 플러그인 jar에 `LICENSE`, `NOTICE`와 서드파티 라이선스 고지를 넣었다. (#45)

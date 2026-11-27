# Support for Reactive Streams 변경 이력

## 미배포

## 0.1.5 (2026-11-27)

- 구독하지 않은 리액티브 값 검사가 오류가 아니라 경고로 표시된다. (#PR)
- 아무것도 돌려주지 않는 람다의 본문(`users.forEach(u -> repository.save(u))`, Kotlin `forEach { repository.save(it) }`)으로 버려진 리액티브 값도 알린다. (#PR)
- 연산자에 넘긴 익명 클래스 안의 블로킹 호출과 `toIterable()`, `toStream()`, RxJava의 다른 `blocking…` 메서드, `Future.get()` / `join()`도 알린다. (#PR)

## 0.1.4 (2026-11-26)

- 표시 이름을 바꿨다: Phantom for Reactive Streams → Support for Reactive Streams. 플러그인 id와 기능은 그대로다. (#136)

## 0.1.3 (2026-11-22)

- 이름을 바꿨다: Reactive Streams Support → Phantom for Reactive Streams. Phantom으로 시작하고, 지원하는 프로젝트 이름은 뒤에 둔다. 플러그인 id와 기능은 그대로다.

## 0.1.2 (2026-11-20)

- 플러그인 jar에 `LICENSE`, `NOTICE`와 서드파티 라이선스 고지를 넣었다. (#45)

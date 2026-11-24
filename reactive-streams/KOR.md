# Support for Reactive Streams
`dev.phantom.reactive` · [English](./README.md)

Java와 Kotlin을 위한 Reactor, RxJava 검사입니다.

- **구독하지 않은 리액티브 값 (오류):** `Mono`, `Flux`, 다른 `Publisher`, RxJava 소스를 돌려주는 호출을 문장으로만 쓰면 알려 줍니다. 구독하기 전에는 아무 일도 일어나지 않아서, `reactiveRepository.save(user)`만 쓰면 저장되지 않습니다.
- **리액티브 연산자 안의 블로킹 호출 (경고):** `map`, `flatMap`, `doOnNext` 같은 연산자의 람다 안에서 `block()`, `blockFirst()`, `blockLast()`, RxJava `blockingGet()`, `Thread.sleep()`을 부르면 알려 줍니다.

프로젝트가 Reactor, Reactive Streams, RxJava에 의존하면 Phantom이 이 플러그인 설치를 제안합니다. 다른 프로젝트에서는 아무 동작도 하지 않습니다.

아직 없는 것: 리액티브 디버거 화면(assembly trace).

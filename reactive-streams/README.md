# Phantom Reactive Streams

`dev.phantom.reactive` · [한국어](./KOR.md)

Reactor and RxJava checks for Java and Kotlin.

- **Reactive value not subscribed (error):** a call that returns a `Mono`, `Flux`, another `Publisher` or an RxJava source, used as a statement. Nothing happens until something subscribes, so `reactiveRepository.save(user)` on its own saves nothing.
- **Blocking call inside a reactive operator (warning):** `block()`, `blockFirst()`, `blockLast()`, RxJava `blockingGet()` and `Thread.sleep()` inside the lambda of `map`, `flatMap`, `doOnNext` and other operators.

Phantom suggests this plugin when a project depends on Reactor, Reactive Streams or RxJava. In other projects, it does nothing.

Not yet: the reactive debugger view (assembly traces).

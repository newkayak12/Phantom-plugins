// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.reactive

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class ReactiveInspectionsTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    myFixture.addClass("package org.reactivestreams; public interface Publisher<T> {}")
    myFixture.addClass("""
      package reactor.core.publisher;
      public abstract class Mono<T> implements org.reactivestreams.Publisher<T> {
        public static <T> Mono<T> just(T t) { return null; }
        public <R> Mono<R> map(java.util.function.Function<T, R> f) { return null; }
        public <R> Mono<R> flatMap(java.util.function.Function<T, Mono<R>> f) { return null; }
        public Mono<T> doOnNext(java.util.function.Consumer<T> c) { return null; }
        public T block() { return null; }
        public reactor.core.Disposable subscribe() { return null; }
      }
    """.trimIndent())
    myFixture.addClass("package reactor.core; public interface Disposable {}")
    myFixture.addClass("package app; public interface UserRepository { reactor.core.publisher.Mono<String> save(String user); }")
    myFixture.enableInspections(UnusedPublisherInspection::class.java, BlockingInReactiveInspection::class.java)
  }

  private fun problems(): List<String> =
    myFixture.doHighlighting(HighlightSeverity.WARNING).mapNotNull(HighlightInfo::getDescription).filterNot { it.startsWith("[") }

  fun testJava() {
    myFixture.configureByText("Service.java", """
      package app;
      import reactor.core.publisher.Mono;
      class Service {
        UserRepository repository;
        Mono<String> ok(String user) { return repository.save(user).map(s -> s.trim()); }
        void dropped(String user) {
          repository.save(user);
          repository.save(user).map(s -> s.trim());
          repository.save(user).subscribe();
          Mono<String> kept = repository.save(user);
        }
        Mono<String> blocking(String user) {
          String early = Mono.just(user).block();
          return Mono.just(user).flatMap(u -> repository.save(u).map(s -> Mono.just(s).block()));
        }
      }
    """.trimIndent())
    val found = problems()
    assertEquals(found.toString(), 2, found.count { it.startsWith("The result of 'save'") || it.startsWith("The result of 'map'") })
    assertEquals(found.toString(), listOf("'block' blocks a reactive scheduler thread. Use flatMap or move the blocking work to Schedulers.boundedElastic()."),
                 found.filter { "blocks" in it })
  }

  fun testKotlin() {
    myFixture.configureByText("Service.kt", """
      package app
      import reactor.core.publisher.Mono

      class Service(private val repository: UserRepository) {
          fun ok(user: String): Mono<String> = repository.save(user)
          fun lambdaValue(user: String): Mono<String> = Mono.just(user).flatMap { repository.save(it) }
          fun dropped(user: String) {
              repository.save(user)
              val kept = repository.save(user)
              Mono.just(user).doOnNext { Thread.sleep(10) }.subscribe()
          }
      }
    """.trimIndent())
    val found = problems()
    assertEquals(found.toString(), 1, found.count { it.startsWith("The result of 'save'") })
    assertEquals(found.toString(), 1, found.count { it.startsWith("'sleep' blocks") })
  }
}

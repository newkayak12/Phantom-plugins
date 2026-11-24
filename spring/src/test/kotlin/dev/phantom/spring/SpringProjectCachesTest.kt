// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.ide.SpringCacheOperations
import dev.phantom.spring.core.ide.SpringEventModel

/** The project-wide cache-operation and event-listener searches survive typing in bodies and follow declaration edits. */
class SpringProjectCachesTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  private lateinit var caches: StructureCaches

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
    caches = StructureCaches(myFixture)
  }

  fun testListenersAndCacheOperationsKeptAcrossBodyEdits() {
    val service = myFixture.addFileToProject("app/Service.java", """
      package app;
      public class Service {
        @org.springframework.context.event.EventListener void on(String event) { int a = 1; }
        @org.springframework.cache.annotation.Cacheable("users") Object find() { return null; }
      }
    """.trimIndent()).virtualFile
    val plain = myFixture.addFileToProject("app/Plain.java", "package app; public class Plain { void m() { int x = 1; } }").virtualFile
    val listeners = SpringEventModel.listeners(project)
    val operations = SpringCacheOperations.all(project)
    assertEquals(1, listeners.size)
    assertEquals(1, operations.size)

    caches.edit(plain, "int x = 1;", "int x = 2;")
    caches.edit(service, "int a = 1;", "int a = 2;")
    assertSame(listeners, SpringEventModel.listeners(project))
    assertSame(operations, SpringCacheOperations.all(project))

    caches.edit(service, "return null; }\n}", "return null; }\n  @org.springframework.context.event.EventListener void more(Integer event) {}\n}")
    assertEquals(2, SpringEventModel.listeners(project).size)
    caches.edit(service, "{}\n}", "{}\n  @org.springframework.cache.annotation.Cacheable(\"orders\") Object orders() { return null; }\n}")
    assertEquals(2, SpringCacheOperations.all(project).size)
  }
}

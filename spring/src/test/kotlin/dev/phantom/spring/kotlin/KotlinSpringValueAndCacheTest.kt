// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.kotlin

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.RealJdkWithKotlin
import dev.phantom.spring.SpringStubs
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.ide.*

class KotlinSpringValueAndCacheTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdkWithKotlin

  private val tools = arrayOf(
    SpringValuePlaceholderInspection(), SpringElSyntaxInspection(), SpringCacheNamesInspection(), SpringCacheOnInterfaceInspection(),
    SpringQualifierUnresolvedInspection(), SpringDependsOnUnresolvedInspection(),
  )

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
    myFixture.enableInspections(*tools)
  }

  private fun msg(key: String, vararg params: Any) = PhantomSpringBundle.message(key, *params)

  private fun problems(): List<String> = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
    .filter { it.description != null && it.inspectionToolId in tools.map { t -> t.shortName } }
    .map(HighlightInfo::getDescription).sorted()

  fun testKotlinConstructorPropertiesAndInterfaces() {
    myFixture.addFileToProject("application.yaml", "reservation:\n  payment-timeout: 10s\n")
    myFixture.addFileToProject("app/Executors.kt", """
      package app
      @org.springframework.context.annotation.Configuration
      class Executors {
          @org.springframework.context.annotation.Bean
          fun outboxExecutor(): java.util.concurrent.Executor = TODO()
      }
    """.trimIndent())
    myFixture.configureByText("Relay.kt", """
      package app
      import org.springframework.beans.factory.annotation.Qualifier
      import org.springframework.beans.factory.annotation.Value
      import org.springframework.cache.annotation.Cacheable

      @org.springframework.stereotype.Service
      class Relay(
          @Value("\${'$'}{reservation.payment-timeout}") private val timeout: String,
          @Value("\${'$'}{reservation.missing}") private val missing: String,
          @Value("#{1 + (2}") private val broken: String,
          @Qualifier("outboxExecuter") private val executor: java.util.concurrent.Executor,
      ) {
          @Cacheable fun nameless(): String = ""
          @Cacheable("menu", key = "#id") fun named(id: Long): String = ""
      }

      interface MenuPort {
          @Cacheable("menu") fun find(id: Long): String
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.bean.name.typo.message", "outboxExecuter", "outboxExecutor"),
      msg("inspection.cache.interface.message", "@Cacheable"),
      msg("inspection.cache.names.message", "@Cacheable"),
      msg("inspection.spel.message", msg("inspection.spel.unclosed", "(")),
      msg("inspection.value.placeholder.message", "reservation.missing"),
    ).sorted(), problems())
  }
}

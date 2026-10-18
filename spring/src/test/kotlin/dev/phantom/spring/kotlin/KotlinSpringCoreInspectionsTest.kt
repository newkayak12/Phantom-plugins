// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.kotlin

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.RealJdkWithKotlin
import dev.phantom.spring.SpringStubs
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.ide.*

/** The user's style: Kotlin, constructor injection, kotlin-spring applied from the root build file, transactional outbox listeners. */
class KotlinSpringCoreInspectionsTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdkWithKotlin

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
    myFixture.enableInspections(*tools)
  }

  private val tools = arrayOf(
    SpringSelfInvocationInspection(), SpringProxyMethodInspection(), SpringScheduledInspection(), SpringAsyncInspection(),
    SpringEventListenerInspection(), SpringFieldInjectionInspection(), SpringStaticInjectionInspection(),
    SpringDuplicateBeanNameInspection(), SpringScopeInspection(), KotlinSpringOpenInspection(),
  )

  private fun msg(key: String, vararg params: Any) = PhantomSpringBundle.message(key, *params)

  private fun problems(): List<String> = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
    .filter { it.severity != HighlightSeverity.INFORMATION && it.description != null && it.inspectionToolId in tools.map { t -> t.shortName } }
    .map(HighlightInfo::getDescription).sorted()

  private fun withKotlinSpringPlugin() {
    myFixture.addFileToProject("build.gradle.kts", """
      subprojects {
          apply(plugin = "org.jetbrains.kotlin.plugin.spring")
      }
    """.trimIndent())
  }

  /** Shaped like TimeTableOccupiedDomainEventListener: no false positives with the plugin applied. */
  fun testOutboxListenerIsClean() {
    withKotlinSpringPlugin()
    myFixture.addFileToProject("app/Events.kt", """
      package app
      class TimeTableOccupiedDomainEvent(val id: Long)
      class TimeTableOccupiedOutboxEvent(val outboxId: Long)
    """.trimIndent())
    myFixture.configureByText("Listener.kt", """
      package app
      import org.springframework.context.ApplicationEventPublisher
      import org.springframework.stereotype.Component
      import org.springframework.transaction.annotation.Propagation.REQUIRES_NEW
      import org.springframework.transaction.annotation.Transactional
      import org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT
      import org.springframework.transaction.event.TransactionPhase.BEFORE_COMMIT
      import org.springframework.transaction.event.TransactionalEventListener
      import org.springframework.scheduling.annotation.Scheduled
      import org.springframework.scheduling.annotation.Async

      @Component
      class TimeTableOccupiedDomainEventListener(
          private val applicationEventPublisher: ApplicationEventPublisher,
      ) {
          companion object {
              const val VERSION = 1.0
          }

          @TransactionalEventListener(phase = BEFORE_COMMIT)
          fun handle(event: TimeTableOccupiedDomainEvent) {
              applicationEventPublisher.publish<caret>Event(TimeTableOccupiedOutboxEvent(event.id))
          }

          @TransactionalEventListener(phase = AFTER_COMMIT)
          @Transactional(propagation = REQUIRES_NEW)
          fun publishKafkaEvent(outboxEvent: TimeTableOccupiedOutboxEvent) {}

          @Scheduled(fixedDelayString = "\${'$'}{reservation.timetable.hold-expiry-interval-millis:10000}")
          fun expire() {}

          @Async
          fun later() {}

          @Async
          suspend fun suspended(): String = ""
      }
    """.trimIndent())
    assertEmpty(problems())
    val gutter = myFixture.findGuttersAtCaret().single { it.tooltipText == msg("event.gutter.listeners", "TimeTableOccupiedOutboxEvent") }
    assertNotNull(gutter)
  }

  fun testSelfCallAndPrivateTransactional() {
    withKotlinSpringPlugin()
    myFixture.configureByText("Reservations.kt", """
      package app
      import org.springframework.stereotype.Service
      import org.springframework.transaction.annotation.Transactional

      @Service
      class Reservations {
          fun reserve() {
              confirm()
              this.confirm()
              val r = Runnable { confirm() }
          }

          @Transactional
          fun confirm() {}

          @Transactional
          private fun hidden() {}
      }
    """.trimIndent())
    val self = msg("inspection.self.invocation.message", "@Transactional", "confirm")
    assertEquals(listOf(msg("inspection.proxy.method.message", "@Transactional", "private"), self, self, self), problems())
  }

  fun testWithoutThePluginKotlinClassesAreFinal() {
    myFixture.configureByText("Config.kt", """
      package app
      import org.springframework.context.annotation.Bean
      import org.springframework.context.annotation.Configuration
      import org.springframework.stereotype.Service
      import org.springframework.transaction.annotation.Transactional

      @Configuration
      class AppConfig {
          @Bean fun clock(): Any = Any()
      }

      @Configuration(proxyBeanMethods = false)
      class LiteConfig

      @Service
      open class Payments {
          @Transactional fun pay() {}
          @Transactional open fun refund() {}
      }

      @Service
      class Plain {
          fun x() {}
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.kotlin.final.class", "AppConfig"),
      msg("inspection.kotlin.final.method", "@Transactional"),
    ).sorted(), problems())
  }

  fun testRedundantOpenWithThePlugin() {
    withKotlinSpringPlugin()
    myFixture.configureByText("Svc.kt", """
      package app
      @org.springframework.stereotype.Service
      open class Svc {
          open fun run() {}
      }
      open class NotABean
    """.trimIndent())
    assertEquals(listOf(msg("inspection.kotlin.redundant.open"), msg("inspection.kotlin.redundant.open")), problems())
    myFixture.configureByText("Svc2.kt", "package app\n@org.springframework.stereotype.Service\n<caret>open class Svc2")
    myFixture.launchAction(myFixture.findSingleIntention(msg("inspection.kotlin.remove.open")))
    assertFalse(myFixture.editor.document.text.contains("open class"))
  }

  fun testLateinitFieldInjectionInProductionCode() {
    withKotlinSpringPlugin()
    myFixture.addFileToProject("app/Repo.kt", "package app\n@org.springframework.stereotype.Component class Repo")
    myFixture.configureByText("Svc.kt", """
      package app
      import org.springframework.beans.factory.annotation.Autowired
      @org.springframework.stereotype.Service
      class Svc(private val repo: Repo) {
          @Autowired lateinit var other: Repo
      }
    """.trimIndent())
    assertEquals(listOf(msg("inspection.field.injection.message")), problems())
  }
}

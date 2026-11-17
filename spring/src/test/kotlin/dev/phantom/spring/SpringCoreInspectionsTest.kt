// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.ide.*
import org.jetbrains.uast.toUElementOfType

class SpringCoreInspectionsTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
  }

  private fun msg(key: String, vararg params: Any) = PhantomSpringBundle.message(key, *params)

  fun testSelfInvocation() {
    myFixture.enableInspections(SpringSelfInvocationInspection())
    val tx = msg("inspection.self.invocation.message", "@Transactional", "place")
    val txNew = msg("inspection.self.invocation.message", "@Transactional", "audit")
    val async = msg("inspection.self.invocation.message", "@Async", "notifyLater")
    myFixture.configureByText("Orders.java", """
      package app;
      import org.springframework.transaction.annotation.*;
      import org.springframework.scheduling.annotation.Async;
      @org.springframework.stereotype.Service
      public class Orders {
        @Transactional public void place() {}
        @Transactional(propagation = Propagation.REQUIRES_NEW) public void audit() {}
        @Async public void notifyLater() {}
        public void plain() {}
        public void outside() { <warning descr="$tx">place</warning>(); this.<warning descr="$async">notifyLater</warning>(); plain(); }
        @Transactional public void inside() { place(); <warning descr="$txNew">audit</warning>(); }
        public void viaOther(Orders other) { other.place(); }
        public void inAnonymous() { new Runnable() { public void run() { } }.run(); }
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testProxyAnnotationOnMethodsTheProxyCannotOverride() {
    myFixture.enableInspections(SpringProxyMethodInspection())
    myFixture.configureByText("Svc.java", """
      package app;
      import org.springframework.transaction.annotation.Transactional;
      import org.springframework.scheduling.annotation.Async;
      import org.springframework.cache.annotation.Cacheable;
      @org.springframework.stereotype.Service
      public class Svc {
        @Transactional private void <warning descr="${msg("inspection.proxy.method.message", "@Transactional", "private")}">hidden</warning>() {}
        @Async public static void <warning descr="${msg("inspection.proxy.method.message", "@Async", "static")}">later</warning>() {}
        @Cacheable public final String <warning descr="${msg("inspection.proxy.method.message", "@Cacheable", "final")}">cached</warning>() { return ""; }
        @Transactional public void fine() {}
      }
      interface Port { @Transactional void run(); }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testScheduledContract() {
    myFixture.enableInspections(SpringScheduledInspection())
    myFixture.configureByText("Jobs.java", """
      package app;
      import org.springframework.scheduling.annotation.Scheduled;
      public class Jobs {
        @Scheduled(fixedDelay = 1000) public void <error descr="${msg("inspection.scheduled.parameters")}">withArg</error>(String s) {}
        <error descr="${msg("inspection.scheduled.none")}">@Scheduled</error> public void none() {}
        <error descr="${msg("inspection.scheduled.several", "cron, fixedRate")}">@Scheduled(cron = "0 0 * * * *", fixedRate = 5)</error> public void two() {}
        @Scheduled(cron = <error descr="${msg("inspection.scheduled.cron", "5 fields; Spring cron has 6: second minute hour day-of-month month day-of-week")}">"0 * * * *"</error>) public void unixCron() {}
        @Scheduled(cron = "0 0/5 9-17 * * MON-FRI") public void ok1() {}
        @Scheduled(cron = "@daily") public void ok2() {}
        @Scheduled(cron = "${'$'}{jobs.cron}") public void ok3() {}
        @Scheduled(fixedDelayString = "${'$'}{reservation.interval:10000}") public void ok4() {}
        @Scheduled(initialDelay = 1000) public void once() {}
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testAsyncReturnAndEventListenerContract() {
    myFixture.enableInspections(SpringAsyncInspection(), SpringEventListenerInspection())
    myFixture.configureByText("Handlers.java", """
      package app;
      import org.springframework.scheduling.annotation.Async;
      import org.springframework.context.event.EventListener;
      public class Handlers {
        @Async public <warning descr="${msg("inspection.async.return", "String")}">String</warning> lost() { return ""; }
        @Async public java.util.concurrent.CompletableFuture<String> kept() { return null; }
        @Async public void fire() {}
        @EventListener public void <error descr="${msg("inspection.event.listener.parameters", 2)}">two</error>(String a, String b) {}
        @EventListener public void <error descr="${msg("inspection.event.listener.no.event")}">none</error>() {}
        @EventListener(classes = String.class) public void named() {}
        @org.springframework.transaction.event.TransactionalEventListener public void one(Integer e) {}
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testFieldAndStaticInjection() {
    myFixture.enableInspections(SpringFieldInjectionInspection(), SpringStaticInjectionInspection())
    myFixture.addClass("package app; @org.springframework.stereotype.Component public class Repo {}")
    myFixture.configureByText("Svc.java", """
      package app;
      import org.springframework.beans.factory.annotation.*;
      @org.springframework.stereotype.Service
      public class Svc {
        @Autowired @Qualifier("repo") private Repo <weak_warning descr="${msg("inspection.field.injection.message")}">re<caret>po</weak_warning>;
        @Autowired private static Repo <warning descr="${msg("inspection.static.injection.message")}">shared</warning>;
        @Value("x") private String config;
      }
    """.trimIndent())
    myFixture.checkHighlighting(true, false, true)
    myFixture.launchAction(myFixture.findSingleIntention(msg("inspection.field.injection.fix")))
    val text = myFixture.editor.document.text
    assertTrue(text, text.contains("private final Repo repo;"))
    assertTrue(text, Regex("""public Svc\(@Qualifier\("repo"\) Repo repo\) \{\s+this.repo = repo;\s+}""").containsMatchIn(text))
  }

  fun testDuplicateBeanNamesAndScopes() {
    myFixture.enableInspections(SpringDuplicateBeanNameInspection(), SpringScopeInspection())
    myFixture.addClass("package app; @org.springframework.stereotype.Component(\"clock\") public class SystemClock {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile(\"test\") public class FakeMailer {}")
    val dup = msg("inspection.duplicate.bean.message", "clock", "app.SystemClock")
    myFixture.configureByText("Config.java", """
      package app;
      import org.springframework.context.annotation.*;
      @Configuration
      public class Config {
        @Bean public Object <error descr="$dup">clock</error>() { return null; }
        @Bean public Object fakeMailer() { return null; }
        @Bean @Scope(<warning descr="${msg("inspection.scope.message", "protoype")}">"protoype"</warning>) public Object a() { return null; }
        @Bean @Scope("prototype") public Object b() { return null; }
        @Bean @Scope(scopeName = "refresh") public Object c() { return null; }
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testBeanNameReferences() {
    myFixture.addClass("package app; @org.springframework.stereotype.Component public class StepConfig {}")
    myFixture.configureByText("Job.java", """
      package app;
      @org.springframework.context.annotation.DependsOn("step<caret>Config")
      @org.springframework.stereotype.Component
      public class Job {}
    """.trimIndent())
    val target = myFixture.getReferenceAtCaretPositionWithAssertion().resolve()
    assertEquals("app.StepConfig", (target as com.intellij.psi.PsiClass).qualifiedName)
    myFixture.configureByText("Job2.java", """
      package app;
      @org.springframework.stereotype.Component
      public class Job2 { public Job2(@org.springframework.beans.factory.annotation.Qualifier("<caret>") StepConfig c) {} }
    """.trimIndent())
    assertContainsElements(myFixture.completeBasic().map { it.lookupString }, "stepConfig", "job")
  }

  fun testSqlScriptsAndDirtiesContext() {
    myFixture.enableInspections(SpringSqlScriptInspection(), SpringDirtiesContextInspection())
    myFixture.addFileToProject("app/schema.sql", "create table t(id int);")
    myFixture.addFileToProject("data.sql", "insert into t values (1);")
    myFixture.addClass("package app; @org.springframework.boot.test.context.SpringBootTest public abstract class IntegrationTest {}")
    myFixture.configureByText("RepoTest.java", """
      package app;
      import org.springframework.test.context.jdbc.Sql;
      import org.springframework.test.annotation.DirtiesContext;
      @Sql({"schema.sql", "classpath:data.sql", "/data.sql", "missing.sql"})
      @DirtiesContext
      public class RepoTest extends IntegrationTest {}
      class Plain { @DirtiesContext void x() {} }
    """.trimIndent())
    val weak = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.severity == HighlightSeverity.WEAK_WARNING }.mapNotNull(HighlightInfo::getDescription)
    assertEquals(listOf(msg("inspection.dirties.context.message")), weak)
    val errors = myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR }.mapNotNull(HighlightInfo::getDescription)
    assertEquals(listOf(msg("inspection.sql.script.message", "missing.sql")), errors)
  }

  fun testEventPublishersAndListeners() {
    myFixture.addClass("package app; public class OrderPlaced {}")
    myFixture.addClass("package app; public class Other {}")
    myFixture.addClass("""
      package app;
      @org.springframework.stereotype.Component
      public class Audit {
        @org.springframework.context.event.EventListener public void on(OrderPlaced e) {}
        @org.springframework.transaction.event.TransactionalEventListener public void after(Object any) {}
        @org.springframework.context.event.EventListener public void other(Other o) {}
      }
    """.trimIndent())
    myFixture.configureByText("Orders.java", """
      package app;
      @org.springframework.stereotype.Service
      public class Orders {
        private final org.springframework.context.ApplicationEventPublisher events;
        public Orders(org.springframework.context.ApplicationEventPublisher events) { this.events = events; }
        public void place() { events.publish<caret>Event(new OrderPlaced()); }
      }
    """.trimIndent())
    val gutter = myFixture.findGuttersAtCaret().single { it.tooltipText == msg("event.gutter.listeners", "OrderPlaced") }
    assertNotNull(gutter)
    val psiCall = com.intellij.psi.util.PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), com.intellij.psi.PsiMethodCallExpression::class.java)
    val call = psiCall.toUElementOfType<org.jetbrains.uast.UCallExpression>()!!
    val listeners = SpringEventModel.listenersOf(project, SpringEventModel.publishedType(call)!!).map { it.name }.sorted()
    assertEquals(listOf("after", "on"), listeners)
    val audit = myFixture.findClass("app.Audit")
    val publishers = SpringEventModel.publishersOf(project, SpringEvents.eventTypes(audit.findMethodsByName("on", false).single()))
    assertEquals(1, publishers.size)
    assertEmpty(SpringEventModel.publishersOf(project, SpringEvents.eventTypes(audit.findMethodsByName("other", false).single())))
  }
}

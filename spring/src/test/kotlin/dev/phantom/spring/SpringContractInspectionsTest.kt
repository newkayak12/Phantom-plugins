// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiClass
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.ide.*

/** Wave 8a: @Bean lifecycle methods, @Lookup, Resource values, @Profile, bean name convention, argNames bindings, cache/scheduled/import gutters. */
class SpringContractInspectionsTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  private val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
    listOf(
      "package org.springframework.stereotype; $runtime @Component public @interface Repository { String value() default \"\"; }",
      "package org.springframework.beans.factory.annotation; $runtime public @interface Lookup { String value() default \"\"; }",
      "package org.springframework.context.annotation; $runtime public @interface Import { Class<?>[] value(); }",
      "package org.springframework.test.context; $runtime public @interface ActiveProfiles { String[] value() default {}; String[] profiles() default {}; }",
      "package org.springframework.core.io; public interface Resource {}",
      "package org.springframework.cache.annotation; $runtime public @interface Caching { Cacheable[] cacheable() default {}; CachePut[] put() default {}; CacheEvict[] evict() default {}; }",
      "package app; public class Foo {}",
      "package app; public class Pool { public void start() {} public void shutdown(boolean force) {} public void configure(int size) {} }",
      "package app; public interface Port { void open(); }",
    ).forEach { myFixture.addClass(it) }
  }

  private fun problems(vararg tools: LocalInspectionTool): List<String> {
    myFixture.enableInspections(*tools)
    return myFixture.doHighlighting().filter { it.inspectionToolId != null }.mapNotNull { it.description }.sorted()
  }

  private fun msg(key: String, vararg params: Any) = PhantomSpringBundle.message(key, *params)

  fun testBeanLifecycleMethods() {
    myFixture.configureByText("Config.java", """
      package app;
      import org.springframework.context.annotation.*;
      @Configuration class Config {
        @Bean(initMethod = "start", destroyMethod = "shutdown") Pool ok() { return new Pool(); }
        @Bean(initMethod = "begin") Pool missing() { return new Pool(); }
        @Bean(initMethod = "configure") Pool withParameters() { return new Pool(); }
        @Bean(destroyMethod = "") Pool noDestroy() { return new Pool(); }
        @Bean(initMethod = "${'$'}{init.method}") Pool placeholder() { return new Pool(); }
        @Bean(initMethod = "whatever") Port anInterface() { return null; }
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.bean.lifecycle.missing", "begin", "Pool", "initMethod"),
      msg("inspection.bean.lifecycle.parameters", "configure", "Pool", "initMethod"),
    ).sorted(), problems(SpringBeanLifecycleMethodInspection()))
  }

  fun testLookupMethods() {
    myFixture.configureByText("Lookups.java", """
      package app;
      import org.springframework.beans.factory.annotation.Lookup;
      import org.springframework.stereotype.Component;
      @Component abstract class Lookups {
        @Lookup abstract Foo ok();
        @Lookup protected Foo alsoOk() { return null; }
        @Lookup private Foo hidden() { return null; }
        @Lookup final Foo fixed() { return null; }
        @Lookup abstract void nothing();
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.lookup.modifier", "final"),
      msg("inspection.lookup.modifier", "private"),
      msg("inspection.lookup.void"),
    ).sorted(), problems(SpringLookupMethodInspection()))
  }

  fun testResourceValues() {
    myFixture.addFileToProject("templates/mail.html", "<p/>")
    myFixture.configureByText("Templates.java", """
      package app;
      import org.springframework.beans.factory.annotation.Value;
      import org.springframework.core.io.Resource;
      class Templates {
        @Value("classpath:templates/mail.html") Resource mail;
        @Value("classpath:templates/missing.html") Resource missing;
        @Value("clas:templates/mail.html") Resource typo;
        @Value("classpath*:templates/*.html") Resource[] all;
        @Value("file:/etc/app.conf") Resource file;
        @Value("C:/data/app.conf") Resource windows;
        @Value("${'$'}{template.location}") Resource placeholder;
        @Value("templates/mail.html") Resource plain;
        @Value("clas:not-a-resource") String text;
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.resource.value.missing", "classpath:templates/missing.html"),
      msg("inspection.resource.value.prefix", "clas:"),
    ).sorted(), problems(SpringResourceValueInspection()))
  }

  fun testProfileExpressions() {
    assertNull(SpringProfileExpressions.problem("dev"))
    assertNull(SpringProfileExpressions.problem("!prod"))
    assertNull(SpringProfileExpressions.problem("dev & (cloud | local)"))
    assertNull(SpringProfileExpressions.problem("a | b | c"))
    assertNotNull(SpringProfileExpressions.problem(""))
    assertNotNull(SpringProfileExpressions.problem("a & b | c"))
    assertNotNull(SpringProfileExpressions.problem("(a & b"))
    assertNotNull(SpringProfileExpressions.problem("a &"))
    assertNotNull(SpringProfileExpressions.problem("a )"))
    myFixture.configureByText("Profiles.java", """
      package app;
      import org.springframework.context.annotation.Profile;
      @Profile({"dev", "a & b | c"}) class Profiles {}
    """.trimIndent())
    assertEquals(listOf(msg("inspection.profile.expression.message", "mixing '&' and '|' needs parentheses")), problems(SpringProfileExpressionInspection()))
  }

  fun testUnknownProfiles() {
    myFixture.addFileToProject("application.yaml", """
      spring:
        profiles:
          active:
            - temporary
            - local
          group:
            cloud: aws, gcp
    """.trimIndent())
    myFixture.addFileToProject("application-stage.yml", "x: 1\n")
    myFixture.addFileToProject("security.yaml", "spring:\n  config:\n    activate:\n      on-profile: production\n")
    myFixture.addFileToProject("application.properties", "spring.config.import=classpath:security.yaml\n")
    myFixture.addClass("package app; @org.springframework.test.context.ActiveProfiles(\"test\") class SomeTest {}")
    myFixture.configureByText("Beans.java", """
      package app;
      import org.springframework.context.annotation.Profile;
      @Profile({"temporary", "local", "stage", "production"}) class Known {}
      @Profile("test") class ForTests {}
      @Profile("!default & aws") class Cloud {}
      @Profile("prodution") class Typo {}
    """.trimIndent())
    assertEquals(listOf(msg("inspection.profile.unknown.message", "prodution")), problems(SpringUnknownProfileInspection()))
  }

  fun testUnknownProfilesWithoutConfigFiles() {
    myFixture.configureByText("Beans.java", """
      package app;
      @org.springframework.context.annotation.Profile("anything") class Any {}
    """.trimIndent())
    assertEquals(emptyList<String>(), problems(SpringUnknownProfileInspection()))
  }

  fun testBeanNameConvention() {
    myFixture.configureByText("Names.java", """
      package app;
      import org.springframework.context.annotation.*;
      import org.springframework.stereotype.*;
      @Service("UserService") class A {}
      @Service("userService2") class B {}
      @Component("URLFetcher") class C {}
      @Configuration class Config {
        @Bean(name = "Pool") Pool pool() { return new Pool(); }
        @Bean("pool2") Pool other() { return new Pool(); }
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.bean.name.convention.message", "Pool", "pool"),
      msg("inspection.bean.name.convention.message", "UserService", "userService"),
    ).sorted(), problems(SpringBeanNameConventionInspection()))
  }

  fun testArgNamesBindings() {
    val p = "package org.aspectj.lang.annotation;"
    listOf(
      "$p $runtime public @interface Aspect { String value() default \"\"; }",
      "$p $runtime public @interface Before { String value(); String argNames() default \"\"; }",
      "$p $runtime public @interface AfterReturning { String value() default \"\"; String pointcut() default \"\"; String returning() default \"\"; String argNames() default \"\"; }",
      "package org.aspectj.lang; public interface JoinPoint {}",
    ).forEach { myFixture.addClass(it) }
    myFixture.configureByText("Audit.java", """
      package app;
      import org.aspectj.lang.annotation.*;
      @Aspect class Audit {
        @Before(value = "execution(* app..*(..)) && args(id, name)", argNames = "id,name") void ok(long id, String name) {}
        @Before(value = "execution(* app..*(..)) && args(id)", argNames = "id,user") void unbound(long id, String user) {}
        @AfterReturning(pointcut = "execution(* app..*(..)) && args(id)", returning = "result", argNames = "id,result") void returned(long id, Object result) {}
      }
    """.trimIndent())
    assertEquals(listOf(msg("aop.arg.names.unbound", "user")), problems(AopArgNamesBindingInspection()))
  }

  fun testKotlinContracts() {
    myFixture.addFileToProject("application.yml", "spring:\n  profiles:\n    active: local\n")
    myFixture.configureByText("Kotlin.kt", """
      package app
      import org.springframework.beans.factory.annotation.Lookup
      import org.springframework.beans.factory.annotation.Value
      import org.springframework.context.annotation.*
      import org.springframework.core.io.Resource
      import org.springframework.stereotype.Service
      @Configuration
      class KotlinConfig {
          @Bean(initMethod = "begin") fun pool(): Pool = Pool()
          @Bean(initMethod = "start") fun goodPool(): Pool = Pool()
      }
      @Service("OrderService")
      @Profile("lokal")
      abstract class Orders(@Value("clas:x.txt") val template: Resource) {
          @Lookup abstract fun foo(): Foo
          @Lookup open fun nothing() {}
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.bean.lifecycle.missing", "begin", "Pool", "initMethod"),
      msg("inspection.bean.name.convention.message", "OrderService", "orderService"),
      msg("inspection.lookup.void"),
      msg("inspection.profile.unknown.message", "lokal"),
      msg("inspection.resource.value.prefix", "clas:"),
    ).sorted(), problems(SpringBeanLifecycleMethodInspection(), SpringLookupMethodInspection(), SpringResourceValueInspection(),
                         SpringUnknownProfileInspection(), SpringBeanNameConventionInspection()))
  }

  private fun gutters(): List<GutterMark> = myFixture.findAllGutters()

  private fun targetsOf(tooltip: String): List<String> {
    val mark = gutters().firstOrNull { runCatching { it.tooltipText }.getOrNull() == tooltip } ?: error("no gutter '$tooltip' in ${gutters().map { runCatching { it.tooltipText }.getOrNull() }}")
    val info = (mark as LineMarkerInfo.LineMarkerGutterIconRenderer<*>).lineMarkerInfo as RelatedItemLineMarkerInfo<*>
    return info.createGotoRelatedItems().mapNotNull { it.element }.map { e -> (e as? PsiMethod)?.let { "${it.containingClass?.name}.${it.name}" } ?: (e as? PsiClass)?.name ?: e.text }.sorted()
  }

  fun testCacheGutter() {
    myFixture.addClass("""
      package app;
      import org.springframework.cache.annotation.*;
      @CacheConfig(cacheNames = "orders") class OrderAdmin {
        @CacheEvict(allEntries = true) public void clear() {}
        @Caching(evict = { @CacheEvict("users") }) public void dropUsers() {}
      }
    """.trimIndent())
    myFixture.configureByText("Orders.java", """
      package app;
      import org.springframework.cache.annotation.*;
      class Orders {
        @Cacheable("orders") public String find(long id) { return ""; }
        @CachePut(cacheNames = "orders") public String save(long id) { return ""; }
        @Cacheable("users") public String user(long id) { return ""; }
      }
    """.trimIndent())
    myFixture.doHighlighting()
    val orders = msg("cache.gutter.tooltip", "orders")
    // find and save in this file; the first is find, whose companions are save and the evict in OrderAdmin (@CacheConfig names)
    assertEquals(2, gutters().count { runCatching { it.tooltipText }.getOrNull() == orders })
    assertEquals(listOf("OrderAdmin.clear", "Orders.save"), targetsOf(orders))
    assertEquals(listOf("OrderAdmin.dropUsers"), targetsOf(msg("cache.gutter.tooltip", "users")))
  }

  fun testScheduledAndImportGutters() {
    myFixture.addClass("package app; @org.springframework.context.annotation.Configuration public class DbConfig {}")
    myFixture.configureByText("Jobs.java", """
      package app;
      import org.springframework.context.annotation.*;
      import org.springframework.scheduling.annotation.Scheduled;
      @Configuration @Import(DbConfig.class) class Jobs {
        @Scheduled(cron = "0 0 * * * *") void hourly() {}
        @Scheduled(fixedRate = 5000, initialDelay = 1000) void often() {}
        void plain() {}
      }
    """.trimIndent())
    myFixture.doHighlighting()
    val tooltips = gutters().mapNotNull { runCatching { it.tooltipText }.getOrNull() }
    assertTrue(tooltips.toString(), msg("scheduled.gutter.tooltip", "cron 0 0 * * * *") in tooltips)
    assertTrue(tooltips.toString(), msg("scheduled.gutter.tooltip", "fixedRate 5000, initialDelay 1000") in tooltips)
    assertEquals(listOf("DbConfig"), targetsOf(msg("import.gutter.tooltip", "DbConfig")))
  }
}

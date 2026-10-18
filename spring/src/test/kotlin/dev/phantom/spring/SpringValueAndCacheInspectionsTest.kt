// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.SpringConfigKeys
import dev.phantom.spring.core.ide.*

class SpringValueAndCacheInspectionsTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
  }

  private fun msg(key: String, vararg params: Any) = PhantomSpringBundle.message(key, *params)
  private fun placeholder(key: String) = msg("inspection.value.placeholder.message", key)
  private fun spel(problem: String) = msg("inspection.spel.message", problem)

  fun testPlaceholderNotInAnyConfigFile() {
    myFixture.enableInspections(SpringValuePlaceholderInspection())
    myFixture.addFileToProject("application.yml", """
      reservation:
        payment-timeout: 10s
        retry:
          max_attempts: 3
      jwt:
        secret: |
          multi
          line: not a key
      servers:
        - a
    """.trimIndent())
    myFixture.addFileToProject("config/application-local.properties", "mail.host=localhost\n")
    myFixture.configureByText("Settings.java", """
      package app;
      import org.springframework.beans.factory.annotation.Value;
      public class Settings {
        @Value("${'$'}{reservation.paymentTimeout}") String timeout;
        @Value("${'$'}{reservation.retry.max-attempts}") int attempts;
        @Value("${'$'}{mail.host}") String mail;
        @Value("${'$'}{servers}") String servers;
        @Value("${'$'}{reservation}") String parent;
        @Value("${'$'}{missing.key:fallback}") String withDefault;
        @Value("${'$'}{JAVA_HOME}") String env;
        @Value("${'$'}{user.home}") String home;
        @Value("${'$'}{a.${'$'}{b}}") String dynamic;
        @Value("${'$'}{<warning descr="${placeholder("jwt.secret.line")}">jwt.secret.line</warning>}") String block;
        @Value("${'$'}{<warning descr="${placeholder("reservation.timeout")}">reservation.timeout</warning>}") String typo;
        @Value("x-${'$'}{mail.host}-${'$'}{<warning descr="${placeholder("other")}">other</warning>}") String second;
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testPlaceholdersWithoutConfigFilesOrWithImportedConfig() {
    myFixture.enableInspections(SpringValuePlaceholderInspection())
    val code = """
      package app;
      public class S { @org.springframework.beans.factory.annotation.Value("${'$'}{anything.at.all}") String v; }
    """.trimIndent()
    myFixture.configureByText("S.java", code)
    myFixture.checkHighlighting()
    myFixture.addFileToProject("application.properties", "spring.config.import=optional:configserver:http://config\n")
    myFixture.configureByText("S2.java", code.replace("class S ", "class S2 "))
    myFixture.checkHighlighting()
  }

  fun testYamlKeys() {
    val keys = SpringConfigKeys.yamlKeys("""
      spring:
        datasource:
          url: jdbc:mysql://x # comment
        "quoted.key": 1
      ---
      server:
        port: 8080
    """.trimIndent())
    assertEquals(setOf("spring", "spring.datasource", "spring.datasource.url", "spring.quoted.key", "server", "server.port"), keys)
  }

  fun testSpelSyntax() {
    myFixture.enableInspections(SpringElSyntaxInspection())
    myFixture.configureByText("Svc.java", """
      package app;
      import org.springframework.beans.factory.annotation.Value;
      import org.springframework.cache.annotation.*;
      import org.springframework.context.event.EventListener;
      public class Svc {
        @Value("#{systemProperties['user.region']}") String ok1;
        @Value("#{'${'$'}{list}'.split(',')}") String ok2;
        @Value("plain ${'$'}{x}") String ok3;
        @Value(<error descr="${spel(msg("inspection.spel.unclosed", "#{"))}">"#{1 + 2"</error>) String unclosed;
        @Value(<error descr="${spel(msg("inspection.spel.empty"))}">"#{ }"</error>) String empty;
        @Value(<error descr="${spel(msg("inspection.spel.unclosed", "("))}">"#{T(java.lang.Math).max(1, 2}"</error>) String paren;
        @Cacheable(cacheNames = "c", key = "#id + 'it''s'") public String ok4(long id) { return ""; }
        @Cacheable(cacheNames = "c", key = <error descr="${spel(msg("inspection.spel.unclosed.string"))}">"#id + 'x"</error>) public String q(long id) { return ""; }
        @CacheEvict(cacheNames = "c", condition = <error descr="${spel(msg("inspection.spel.unexpected", "]"))}">"#a]"</error>) public void e(int a) {}
        @EventListener(condition = "#event.ok") public void on(Object event) {}
        @EventListener(condition = <error descr="${spel(msg("inspection.spel.unexpected", ")"))}">"#event.ok)"</error>) public void off(Object event) {}
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testCacheNames() {
    myFixture.enableInspections(SpringCacheNamesInspection())
    myFixture.configureByText("Cached.java", """
      package app;
      import org.springframework.cache.annotation.*;
      public class Cached {
        @Cacheable("menu") public String a() { return ""; }
        @Cacheable(cacheNames = {"x", "y"}) public String b() { return ""; }
        @Cacheable(cacheResolver = "mine") public String c() { return ""; }
        <warning descr="${msg("inspection.cache.names.message", "@Cacheable")}">@Cacheable(key = "#id")</warning> public String d(long id) { return ""; }
        <warning descr="${msg("inspection.cache.names.message", "@CacheEvict")}">@CacheEvict(allEntries = true)</warning> public void e() {}
      }
      @CacheConfig(cacheNames = "shop")
      class Configured {
        @Cacheable public String a() { return ""; }
        @CachePut public String b() { return ""; }
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testCacheNamesWithProjectCacheResolver() {
    myFixture.enableInspections(SpringCacheNamesInspection())
    myFixture.addClass("package app; public class MyResolver implements org.springframework.cache.interceptor.CacheResolver {}")
    myFixture.configureByText("Cached.java", """
      package app;
      public class Cached { @org.springframework.cache.annotation.Cacheable public String a() { return ""; } }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testCacheAnnotationsOnInterfaces() {
    myFixture.enableInspections(SpringCacheOnInterfaceInspection())
    myFixture.addClass("package org.springframework.data.repository; public interface Repository<T, ID> {}")
    myFixture.configureByText("Ports.java", """
      package app;
      import org.springframework.cache.annotation.*;
      <weak_warning descr="${msg("inspection.cache.interface.message", "@CacheConfig")}">@CacheConfig(cacheNames = "m")</weak_warning>
      interface MenuPort {
        <weak_warning descr="${msg("inspection.cache.interface.message", "@Cacheable")}">@Cacheable("menu")</weak_warning> String find(long id);
      }
      interface MenuRepository extends org.springframework.data.repository.Repository<String, Long> {
        @Cacheable("menu") String findById(long id);
      }
      class MenuAdapter implements MenuPort { @Cacheable("menu") public String find(long id) { return ""; } }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testQualifierAndDependsOnNearMisses() {
    myFixture.enableInspections(SpringQualifierUnresolvedInspection(), SpringDependsOnUnresolvedInspection())
    myFixture.addClass("""
      package app;
      @org.springframework.context.annotation.Configuration
      public class Executors {
        @org.springframework.context.annotation.Bean public java.util.concurrent.Executor outboxExecutor() { return null; }
      }
    """.trimIndent())
    myFixture.addClass("package app; @org.springframework.stereotype.Component public class CacheWarmer {}")
    myFixture.configureByText("Relay.java", """
      package app;
      import org.springframework.beans.factory.annotation.Qualifier;
      import org.springframework.context.annotation.DependsOn;
      @org.springframework.stereotype.Component
      @DependsOn({"cacheWarmer", <warning descr="${msg("inspection.bean.name.typo.message", "CacheWarmer", "cacheWarmer")}">"CacheWarmer"</warning>, "flyway", "flywayInitializer"})
      public class Relay {
        public Relay(@Qualifier("outboxExecutor") java.util.concurrent.Executor a,
                     @Qualifier(<warning descr="${msg("inspection.bean.name.typo.message", "outboxExecuter", "outboxExecutor")}">"outboxExecuter"</warning>) java.util.concurrent.Executor b,
                     @Qualifier("applicationTaskExecutor") java.util.concurrent.Executor c) {}
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }
}

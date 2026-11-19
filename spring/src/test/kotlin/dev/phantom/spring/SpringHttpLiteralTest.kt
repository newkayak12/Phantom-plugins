// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.ide.*

/** Wave 10b: header names and media types in Spring code, @Value placeholder style, beans injected past a JDK proxy. */
class SpringHttpLiteralTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  private val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
    val web = "package org.springframework.web.bind.annotation;"
    val mapping = "String[] value() default {}; String[] path() default {}; String[] headers() default {}; String[] produces() default {}; String[] consumes() default {};"
    listOf(
      "package org.springframework.stereotype; $runtime @Component public @interface Controller { String value() default \"\"; }",
      "$web $runtime @org.springframework.stereotype.Controller public @interface RestController { String value() default \"\"; }",
      "$web $runtime public @interface RequestMapping { $mapping }",
      "$web $runtime public @interface GetMapping { $mapping }",
      "$web $runtime public @interface PostMapping { $mapping }",
      "$web $runtime public @interface RequestHeader { String value() default \"\"; String name() default \"\"; }",
      "$web $runtime public @interface CrossOrigin { String[] allowedHeaders() default {}; String[] exposedHeaders() default {}; }",
      "package org.springframework.http; public class HttpHeaders { public void set(String name, String value) {} public void add(String name, String value) {} public String getFirst(String name) { return null; } }",
      "package org.springframework.http; public class MediaType { public static MediaType parseMediaType(String s) { return null; } }",
      "package org.springframework.transaction.annotation; $runtime public @interface EnableTransactionManagement { boolean proxyTargetClass() default false; }",
    ).forEach { myFixture.addClass(it) }
  }

  private fun problems(vararg tools: LocalInspectionTool): List<String> {
    myFixture.enableInspections(*tools)
    return myFixture.doHighlighting().filter { it.inspectionToolId != null }.mapNotNull { it.description }.sorted()
  }

  private fun msg(key: String, vararg params: Any) = PhantomSpringBundle.message(key, *params)

  fun testHeaderNamesAndMediaTypes() {
    assertNull(HttpLiterals.headerProblem("Content-Type"))
    assertNull(HttpLiterals.headerProblem("x-tenant-id"))
    assertNull(HttpLiterals.headerProblem("\${app.header}"))
    assertEquals(msg("inspection.http.header.typo", "Content_Type", "Content-Type"), HttpLiterals.headerProblem("Content_Type"))
    assertEquals(msg("inspection.http.header.typo", "Authorisation", "Authorization"), HttpLiterals.headerProblem("Authorisation"))
    assertEquals(msg("inspection.http.header.malformed", "X Tenant"), HttpLiterals.headerProblem("X Tenant"))
    assertNull(HttpLiterals.mediaTypeProblem("application/vnd.acme+json"))
    assertNull(HttpLiterals.mediaTypeProblem("text/*"))
    assertNull(HttpLiterals.mediaTypeProblem("!application/xml"))
    assertNull(HttpLiterals.mediaTypeProblem("application/json;charset=UTF-8"))
    assertEquals(msg("inspection.http.media.typo", "aplication/json", "application/json"), HttpLiterals.mediaTypeProblem("aplication/json"))
    assertEquals(msg("inspection.http.media.typo", "image/jpg", "image/jpeg"), HttpLiterals.mediaTypeProblem("image/jpg"))
    assertEquals(msg("inspection.http.media.malformed", "json"), HttpLiterals.mediaTypeProblem("json"))
    assertEquals(msg("inspection.http.media.unknown.top", "data/csv", "data"), HttpLiterals.mediaTypeProblem("data/csv"))
  }

  fun testInSpringCode() {
    myFixture.configureByText("Api.java", """
      import org.springframework.web.bind.annotation.*;
      import org.springframework.http.*;
      @RestController
      @CrossOrigin(allowedHeaders = {"Content-Type", "Acept"})
      class Api {
        @GetMapping(value = "/a", produces = "aplication/json", headers = "!X-Debug")
        String a(@RequestHeader("Authorisation") String auth, @RequestHeader("X-Tenant") String tenant) { return ""; }
        @PostMapping(value = "/b", consumes = {"application/json", "text/plain"}, headers = {"Content_Type=text/plain"})
        String b() { return ""; }
        void client(HttpHeaders headers) {
          headers.set("Cache-Control", "no-cache");
          headers.set("Content-Type", "aplication/json");
          headers.add("Accept-Encodng", "gzip");
          MediaType.parseMediaType("text/csv");
          MediaType.parseMediaType("textcsv");
        }
        void unrelated(java.util.Map<String, String> map) { map.put("Acept", "x"); }
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.http.header.typo", "Acept", "Accept"),
      msg("inspection.http.header.typo", "Accept-Encodng", "Accept-Encoding"),
      msg("inspection.http.header.typo", "Authorisation", "Authorization"),
      msg("inspection.http.header.typo", "Content_Type", "Content-Type"),
      msg("inspection.http.media.malformed", "textcsv"),
      msg("inspection.http.media.typo", "aplication/json", "application/json"),
      msg("inspection.http.media.typo", "aplication/json", "application/json"),
    ).sorted(), problems(SpringHttpHeaderInspection(), SpringMediaTypeInspection()))
  }

  fun testKotlin() {
    myFixture.configureByText("Api.kt", """
      import org.springframework.web.bind.annotation.*
      @RestController
      class Api {
        @GetMapping("/a", produces = ["application/jsno"])
        fun a(@RequestHeader("User-Agnet") agent: String, @RequestHeader(name = "If-None-Match") tag: String) = ""
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.http.header.typo", "User-Agnet", "User-Agent"),
      msg("inspection.http.media.typo", "application/jsno", "application/json"),
    ).sorted(), problems(SpringHttpHeaderInspection(), SpringMediaTypeInspection()))
  }

  fun testValueStyle() {
    assertNull(SpringValueStyleInspection.problem("\${app.name}"))
    assertNull(SpringValueStyleInspection.problem("#{\${app.timeout} * 2}"))
    assertNull(SpringValueStyleInspection.problem("\${app.name:\${other:x}}"))
    assertNull(SpringValueStyleInspection.problem("plain text {not a key here}"))
    myFixture.configureByText("Settings.java", """
      import org.springframework.beans.factory.annotation.Value;
      class Settings {
        @Value("${'$'}{app.name}") String ok;
        @Value("$ {app.name}") String spaced;
        @Value("${'$'}{app.name") String unclosed;
        @Value("${'$'}{}") String empty;
        @Value("${'$'}{#{1 + 1}}") String spel;
        @Value("{app.name}") String bare;
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.value.style.bare", "{app.name}"),
      msg("inspection.value.style.empty"),
      msg("inspection.value.style.spaced"),
      msg("inspection.value.style.spel.inside"),
      msg("inspection.value.style.unclosed"),
    ).sorted(), problems(SpringValueStyleInspection()))
  }

  private val services = """
    package app;
    import org.springframework.stereotype.Service;
    import org.springframework.transaction.annotation.Transactional;
    public interface OrderService { void place(); }
  """.trimIndent()

  private fun addOrderBeans() {
    myFixture.addClass(services)
    myFixture.addClass("""
      package app;
      @org.springframework.stereotype.Service
      public class OrderServiceImpl implements OrderService {
        @org.springframework.transaction.annotation.Transactional public void place() {}
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      @org.springframework.stereotype.Service
      public class PlainService implements OrderService { public void place() {} }
    """.trimIndent())
  }

  private fun configureClient(): List<String> {
    myFixture.configureByText("Client.java", """
      package app;
      @org.springframework.stereotype.Service
      class Client {
        Client(OrderServiceImpl impl, OrderService service, PlainService plain) {}
      }
    """.trimIndent())
    return problems(SpringJdkProxyInjectionInspection())
  }

  fun testJdkProxyWithoutBoot() {
    addOrderBeans()
    myFixture.addClass("""
      package app;
      @org.springframework.context.annotation.Configuration
      @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass = false)
      class TxConfig {}
    """.trimIndent())
    assertEquals(listOf(msg("inspection.jdk.proxy.message", "OrderServiceImpl", "OrderService")), configureClient())
  }

  fun testClassProxiesSomewhereMeanNothing() {
    addOrderBeans()
    myFixture.addClass("""
      package app;
      @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass = false) class A {}
    """.trimIndent())
    myFixture.addClass("""
      package app;
      @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass = true) class B {}
    """.trimIndent())
    assertEmpty(configureClient())
  }

  fun testBootNeedsTheProperty() {
    addOrderBeans()
    myFixture.addClass("package org.springframework.boot.autoconfigure; $runtime public @interface SpringBootApplication {}")
    myFixture.addClass("""
      package app;
      @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass = false) class A {}
    """.trimIndent())
    assertEmpty(configureClient())
    myFixture.addFileToProject("application.yml", "spring:\n  aop:\n    proxy-target-class: false\n")
    val again = myFixture.doHighlighting().filter { it.inspectionToolId != null }.mapNotNull { it.description }
    assertEquals(listOf(msg("inspection.jdk.proxy.message", "OrderServiceImpl", "OrderService")), again)
  }
}

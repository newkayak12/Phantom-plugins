// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.security.MethodSecurityExpressionInspection

/** Request path completion in the security configuration, and method security expressions, in Java and Kotlin. */
class SpringSecurityTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    SpringMvcEndpointsTest.springWebStubs().forEach { myFixture.addClass(it) }
    val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    myFixture.addClass("package org.springframework.security.core; public interface Authentication {}")
    myFixture.addClass("package org.springframework.security.access.prepost; $runtime public @interface PreAuthorize { String value(); }")
    myFixture.addClass("package org.springframework.security.core.parameters; $runtime public @interface P { String value(); }")
    myFixture.addClass("""
      package org.springframework.security.config.annotation.web.builders;
      public class HttpSecurity {
        public Registry authorizeHttpRequests() { return null; }
        public static class Registry {
          public Rule requestMatchers(String... patterns) { return null; }
        }
        public static class Rule { public Registry permitAll() { return null; } }
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      import org.springframework.web.bind.annotation.*;
      @RestController @RequestMapping("/api/v1/menu")
      public class MenuController {
        @GetMapping("/{id}") public String find(String id) { return ""; }
        @PostMapping public String create() { return ""; }
      }
    """.trimIndent())
    myFixture.enableInspections(MethodSecurityExpressionInspection::class.java)
  }

  private fun completions(): List<String> = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()

  fun testRequestMatcherPathsInJava() {
    myFixture.configureByText("Security.java", """
      import org.springframework.security.config.annotation.web.builders.HttpSecurity;
      class Security { void configure(HttpSecurity http) { http.authorizeHttpRequests().requestMatchers("/api/<caret>"); } }
    """.trimIndent())
    val items = completions()
    assertTrue(items.toString(), items.containsAll(listOf("/api/v1/menu", "/api/v1/menu/{id}", "/api/**", "/api/v1/**", "/api/v1/menu/**")))
  }

  fun testRequestMatcherPathsInKotlin() {
    myFixture.configureByText("Security.kt", """
      import org.springframework.security.config.annotation.web.builders.HttpSecurity
      fun configure(http: HttpSecurity) { http.authorizeHttpRequests().requestMatchers("/api/v1/menu/<caret>") }
    """.trimIndent())
    assertTrue(completions().toString(), "/api/v1/menu/{id}" in completions())
  }

  fun testOtherStringsAreLeftAlone() {
    myFixture.configureByText("Other.java", "class Other { String s = \"/api/<caret>\"; }")
    assertFalse("/api/v1/menu" in completions())
  }

  fun testMethodSecurityCompletion() {
    myFixture.configureByText("Service.java", """
      import org.springframework.security.access.prepost.PreAuthorize;
      class Service { @PreAuthorize("hasRole('ADMIN') or #<caret>") void update(String userId, @org.springframework.security.core.parameters.P("owner") String o) {} }
    """.trimIndent())
    val names = completions()
    assertEquals(names.toString() + myFixture.editor.document.text, listOf("o", "owner", "userId"), names.sorted())

    myFixture.configureByText("Service2.java", """
      import org.springframework.security.access.prepost.PreAuthorize;
      class Service2 { @PreAuthorize("isAuth<caret>") void update() {} }
    """.trimIndent())
    val items = myFixture.completeBasic()?.map { it.lookupString }
    assertEquals(listOf("isAuthenticated()", "isFullyAuthenticated()"), items)
  }

  fun testUnknownArgument() {
    myFixture.configureByText("Service.java", """
      import org.springframework.security.access.prepost.PreAuthorize;
      class Service {
        @PreAuthorize("#userId == principal.id or #root != null") void ok(String userId) {}
        @PreAuthorize("#usrId == principal.id") void typo(String userId) {}
      }
    """.trimIndent())
    val errors = myFixture.doHighlighting(HighlightSeverity.ERROR).mapNotNull(HighlightInfo::getDescription)
    assertEquals(listOf("'#usrId' is not an argument of this method: Spring evaluates it to null"), errors)
  }

  fun testUnknownArgumentInKotlin() {
    myFixture.configureByText("Service.kt", """
      import org.springframework.security.access.prepost.PreAuthorize
      class Service {
          @PreAuthorize("#userId == authentication.name")
          fun ok(userId: String) {}
          @PreAuthorize("#user == authentication.name")
          fun typo(userId: String) {}
      }
    """.trimIndent())
    val errors = myFixture.doHighlighting(HighlightSeverity.ERROR).mapNotNull(HighlightInfo::getDescription).filterNot { it.startsWith("[") }
    assertEquals(listOf("'#user' is not an argument of this method: Spring evaluates it to null"), errors)
  }
}

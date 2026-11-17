// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.ide.SpringMvcPathVariableInspection
import dev.phantom.spring.core.ide.SpringPathVariables

/** Path template variables against @PathVariable parameters, and the handler gutter icon, in Java and Kotlin. */
class SpringMvcPathVariableTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    SpringMvcEndpointsTest.springWebStubs().forEach { myFixture.addClass(it) }
    val retention = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    val web = "package org.springframework.web.bind.annotation;"
    myFixture.addClass("$web $retention public @interface PathVariable { String value() default \"\"; String name() default \"\"; boolean required() default true; }")
    myFixture.addClass("$web $retention public @interface RequestParam { String value() default \"\"; }")
    myFixture.addClass("$web $retention public @interface ModelAttribute { String value() default \"\"; }")
    myFixture.enableInspections(SpringMvcPathVariableInspection::class.java)
  }

  private fun problems(severity: HighlightSeverity): List<String> =
    myFixture.doHighlighting(severity).filter { it.severity == severity }.mapNotNull(HighlightInfo::getDescription).filterNot { it.startsWith("[") }.sorted()

  fun testVariablesOfATemplate() {
    assertEquals(listOf("id", "rest"), SpringPathVariables.variables("/a/{id:[0-9]{2}}/b/{*rest}"))
    assertEquals(listOf("id"), SpringPathVariables.variables("\${api.base}/{id}"))
  }

  fun testJava() {
    myFixture.configureByText("UserController.java", """
      import org.springframework.web.bind.annotation.*;
      import java.util.Map;
      @RestController @RequestMapping("/users/{tenant}")
      class UserController {
        @GetMapping("/{id}") String ok(@PathVariable String tenant, @PathVariable("id") long userId) { return ""; }
        @GetMapping("/{id}/x") String typo(@PathVariable String tenant, @PathVariable long userId) { return ""; }
        @GetMapping("/{id}/y") String unbound(@PathVariable String tenant) { return ""; }
        @GetMapping("/{a}/{b}") String all(@PathVariable Map<String, String> vars) { return ""; }
        @GetMapping({"/one/{id}", "/two/{id}"}) String several(@PathVariable String tenant, @PathVariable(name = "id") String id) { return ""; }
        @GetMapping("/files/{*path}") String rest(@PathVariable String tenant, @PathVariable String path) { return ""; }
        String notAHandler(@PathVariable String nothing) { return ""; }
      }
    """.trimIndent())
    assertEquals(listOf("'userId' is not a variable of the request path /users/{tenant}/{id}/x"), problems(HighlightSeverity.WARNING))
    assertEquals(listOf(
      "Path variable 'id' is not bound to any @PathVariable parameter",
      "Path variable 'id' is not bound to any @PathVariable parameter",
    ), problems(HighlightSeverity.WEAK_WARNING))
  }

  fun testCommandObjectMayBindVariables() {
    myFixture.addClass("package app; public class Query { public String id; }")
    myFixture.configureByText("Q.java", """
      import org.springframework.web.bind.annotation.*;
      @RestController class Q { @GetMapping("/q/{id}") String find(app.Query query) { return ""; } }
    """.trimIndent())
    assertEmpty(problems(HighlightSeverity.WEAK_WARNING))
  }

  fun testKotlin() {
    myFixture.configureByText("ReservationController.kt", """
      import org.springframework.web.bind.annotation.*
      @RestController
      @RequestMapping("/reservations")
      class ReservationController {
          @GetMapping("/{id}")
          fun get(@PathVariable id: Long): String = ""
          @GetMapping("/{id}/seats/{seat}")
          fun seat(@PathVariable id: Long, @PathVariable("seatNo") seat: Int): String = ""
      }
    """.trimIndent())
    assertEquals(listOf("'seatNo' is not a variable of the request path /reservations/{id}/seats/{seat}"), problems(HighlightSeverity.WARNING))
    assertEquals(listOf("Path variable 'seat' is not bound to any @PathVariable parameter"), problems(HighlightSeverity.WEAK_WARNING))
  }

  fun testHandlerGutter() {
    myFixture.configureByText("Api.kt", """
      import org.springframework.web.bind.annotation.*
      @RestController
      @RequestMapping("/api")
      class Api {
          @PostMapping("/orders")
          fun create(): String = ""
          fun helper(): String = ""
      }
    """.trimIndent())
    myFixture.doHighlighting()
    val tooltips = myFixture.findAllGutters().mapNotNull { runCatching { it.tooltipText }.getOrNull() }.filter { it.contains("Endpoints") }
    assertEquals(listOf("POST /api/orders  (click to open the Endpoints tool window)"), tooltips)
  }
}

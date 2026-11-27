// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.SpringMvcEndpoints

class SpringMvcEndpointsTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  companion object {

    /** Spring's web annotations as source stubs. */
    fun springWebStubs(): List<String> {
      val retention = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
      val web = "package org.springframework.web.bind.annotation;"
      val mapping = "String[] value() default {}; String[] path() default {};"
      return listOf(
        "package org.springframework.stereotype; $retention public @interface Component { String value() default \"\"; }",
        "package org.springframework.stereotype; $retention @Component public @interface Controller { String value() default \"\"; }",
        "$web $retention @org.springframework.stereotype.Controller public @interface RestController { String value() default \"\"; }",
        "$web public enum RequestMethod { GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE }",
        "$web $retention public @interface RequestMapping { $mapping RequestMethod[] method() default {}; }",
        "$web $retention public @interface GetMapping { $mapping }",
        "$web $retention public @interface PostMapping { $mapping }",
        "$web $retention public @interface PutMapping { $mapping }",
        "$web $retention public @interface DeleteMapping { $mapping }",
        "$web $retention public @interface PatchMapping { $mapping }",
      )
    }
  }

  override fun setUp() {
    super.setUp()
    springWebStubs().forEach { myFixture.addClass(it) }
  }

  private fun endpoints(): List<String> =
    SpringMvcEndpoints.all(project).map { "${it.methods.ifEmpty { listOf("ANY") }.joinToString("|")} ${it.path} ${it.controllerName}.${it.handlerName}" }

  fun testClassAndMethodMappingsJoin() {
    myFixture.addClass("""
      package app;
      import org.springframework.web.bind.annotation.*;
      @RestController
      @RequestMapping("/api/users")
      public class UserController {
        @GetMapping("/{id}") public String get() { return ""; }
        @PostMapping public String create() { return ""; }
        @RequestMapping(value = {"a", "/b/"}, method = {RequestMethod.PUT, RequestMethod.PATCH}) public void both() {}
        @DeleteMapping(path = "/{id}") public void delete() {}
        public void notMapped() {}
      }
    """.trimIndent())
    assertEquals(listOf(
      "POST /api/users UserController.create",
      "PUT|PATCH /api/users/a UserController.both",
      "PUT|PATCH /api/users/b UserController.both",
      "DELETE /api/users/{id} UserController.delete",
      "GET /api/users/{id} UserController.get",
    ), endpoints())
  }

  /** A method body edit keeps the endpoint list; an edit of a mapping updates it. */
  fun testListFollowsDeclarationsOnly() {
    myFixture.configureByText("PingController.java", """
      package app;
      import org.springframework.web.bind.annotation.*;
      @RestController
      public class PingController {
        @GetMapping("/ping") public String ping() { return "pong"; }
      }
    """.trimIndent())
    assertEquals(listOf("GET /ping PingController.ping"), endpoints())
    val before = SpringMvcEndpoints.all(project)
    typeAfter("return \"pong", "!")
    assertSame(before, SpringMvcEndpoints.all(project))
    typeAfter("@GetMapping(\"/ping", "s")
    assertEquals(listOf("GET /pings PingController.ping"), endpoints())
  }

  /** Types [text] at the end of the first [anchor] in the open file and commits. */
  private fun typeAfter(anchor: String, text: String) {
    myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf(anchor) + anchor.length)
    myFixture.type(text)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
  }

  fun testConstantsAndAnyMethod() {
    myFixture.addClass("""
      package app;
      import org.springframework.web.bind.annotation.*;
      @org.springframework.stereotype.Controller
      public class PageController {
        static final String BASE = "/pages";
        @RequestMapping(BASE + "/home") public String home() { return "home"; }
      }
    """.trimIndent())
    assertEquals(listOf("ANY /pages/home PageController.home"), endpoints())
  }

  fun testOnlyControllersCount() {
    myFixture.addClass("""
      package app;
      import org.springframework.web.bind.annotation.*;
      public class NotAController {
        @GetMapping("/hidden") public void hidden() {}
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      import org.springframework.web.bind.annotation.*;
      @RequestMapping("/orders")
      public interface OrdersApi {
        @GetMapping("/{id}") String get();
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      @org.springframework.web.bind.annotation.RestController
      public class OrdersController implements OrdersApi {
        public String get() { return ""; }
      }
    """.trimIndent())
    assertEquals(listOf("GET /orders/{id} OrdersController.get"), endpoints())
  }

  /** Mappings inherited from an abstract base class, and a class mapping carried by a composed annotation. */
  fun testAbstractBaseAndComposedClassMapping() {
    myFixture.addClass("""
      package app;
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      @org.springframework.web.bind.annotation.RestController
      @org.springframework.web.bind.annotation.RequestMapping("/api")
      public @interface ApiController {}
    """.trimIndent())
    myFixture.addClass("""
      package app;
      public abstract class CrudController { @org.springframework.web.bind.annotation.GetMapping("/{id}") public String get() { return ""; } }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      @ApiController
      public class UserController extends CrudController { @org.springframework.web.bind.annotation.PostMapping("/users") public void create() {} }
    """.trimIndent())
    assertEquals(listOf("POST /api/users UserController.create", "GET /api/{id} UserController.get"), endpoints())
  }

  fun testProviderGroupsByModule() {
    myFixture.addClass("""
      package app;
      @org.springframework.web.bind.annotation.RestController
      public class Ping { @org.springframework.web.bind.annotation.GetMapping("/ping") public String ping() { return "pong"; } }
    """.trimIndent())
    @Suppress("UNCHECKED_CAST")
    val provider = EndpointsProvider.getAvailableProviders(project).single() as EndpointsProvider<Any, Any>
    assertEquals(EndpointsProvider.Status.HAS_ENDPOINTS, provider.getStatus(project))
    val group = provider.getEndpointGroups(project, object : com.intellij.microservices.endpoints.EndpointsFilter {}).single()
    val endpoint = provider.getEndpoints(group).single()
    val presentation = provider.getEndpointPresentation(group, endpoint)
    assertEquals("/ping", presentation.presentableText)
    assertEquals("Ping.ping", presentation.locationString)
    assertEquals("ping", (provider.getNavigationElement(group, endpoint) as com.intellij.psi.PsiMethod).name)
  }

  fun testJoin() {
    assertEquals("/", SpringMvcEndpoints.join("", ""))
    assertEquals("/a/b", SpringMvcEndpoints.join("/a/", "/b"))
    assertEquals("/a", SpringMvcEndpoints.join("a", ""))
  }

  fun testReadablePath() {
    assertEquals("/api/v1/menu/{id}/all", SpringMvcEndpoints.readablePath("/api/v1/menu/{id:[0-9a-fA-F\\-]{36}}/all"))
    assertEquals("/a/{x}/{y}", SpringMvcEndpoints.readablePath("/a/{x}/{y:\\d+}"))
    assertEquals("/plain", SpringMvcEndpoints.readablePath("/plain"))
  }
}

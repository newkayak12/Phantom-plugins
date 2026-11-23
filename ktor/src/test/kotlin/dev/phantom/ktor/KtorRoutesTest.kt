// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ktor

import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class KtorRoutesTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = KotlinProject

  override fun setUp() {
    super.setUp()
    // The routing DSL's shape, as source: the real functions live in io.ktor.server.routing
    myFixture.addFileToProject("io/ktor/server/routing/RoutingBuilder.kt", """
      package io.ktor.server.routing

      interface Route
      interface Routing : Route
      class Application

      fun Application.routing(configuration: Routing.() -> Unit): Routing = TODO()
      fun Route.route(path: String, build: Route.() -> Unit): Route = TODO()
      fun Route.get(path: String, body: suspend () -> Unit): Route = TODO()
      fun Route.get(body: suspend () -> Unit): Route = TODO()
      fun Route.post(path: String, body: suspend () -> Unit): Route = TODO()
      fun Route.post(body: suspend () -> Unit): Route = TODO()
      fun Route.delete(path: String, body: suspend () -> Unit): Route = TODO()
    """.trimIndent())
  }

  private fun routes(): List<String> = KtorRoutes.all(project).map { "${it.method} ${it.path}" }

  /** Routes are calls inside function bodies: an edit there updates them, an edit in a YAML file does not search again. */
  fun testRoutesFollowKotlinEditsOnly() {
    myFixture.configureByText("Application.kt", """
      package app
      import io.ktor.server.routing.*

      fun Application.module() {
          routing {
              get("/health") { }<caret>
          }
      }
    """.trimIndent())
    myFixture.configureByText("notes.yaml", "a: <caret>1\n")
    assertSameElements(routes(), "GET /health")
    val before = KtorRoutes.all(project)
    myFixture.type("2")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertSame(before, KtorRoutes.all(project))
    myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("Application.kt"))
    myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("get(\"/health\") { }") + "get(\"/health\") { }".length)
    myFixture.type("\n        post(\"/items\") { }")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertSameElements(routes(), "GET /health", "POST /items")
  }

  fun testNestedRoutes() {
    myFixture.addFileToProject("app/Application.kt", """
      package app
      import io.ktor.server.routing.*

      const val API = "/api/v1"

      fun Application.module() {
          routing {
              get("/health") { }
              route(API) {
                  route("menus") {
                      get { }
                      get("{id}") { }
                      post { }
                  }
              }
          }
      }
    """.trimIndent())
    assertSameElements(routes(), "GET /health", "GET /api/v1/menus", "GET /api/v1/menus/{id}", "POST /api/v1/menus")
  }

  fun testExtensionFunctionsTakeTheCallersPrefix() {
    myFixture.addFileToProject("app/UserRoutes.kt", """
      package app
      import io.ktor.server.routing.*

      fun Route.userRoutes() {
          route("/users") {
              get("/{id}") { }
              delete("/{id}") { }
          }
      }
    """.trimIndent())
    myFixture.addFileToProject("app/Application.kt", """
      package app
      import io.ktor.server.routing.*

      fun Application.module() {
          routing {
              route("/api") { userRoutes() }
              route("/admin") { userRoutes() }
          }
      }
    """.trimIndent())
    assertSameElements(routes(), "GET /api/users/{id}", "DELETE /api/users/{id}", "GET /admin/users/{id}", "DELETE /admin/users/{id}")
  }

  fun testEndpointsProviderReportsRoutes() {
    myFixture.addFileToProject("app/Application.kt", """
      package app
      import io.ktor.server.routing.*
      fun Application.module() { routing { get("/ping") { } } }
    """.trimIndent())
    val provider = EndpointsProvider.getAvailableProviders(project).filterIsInstance<KtorEndpointsProvider>().single()
    assertEquals(EndpointsProvider.Status.HAS_ENDPOINTS, provider.getStatus(project))
  }

  fun testJoin() {
    assertEquals("/a/b/{id}", KtorRoutes.join(listOf("/a/", "b", "/{id}")))
    assertEquals("/", KtorRoutes.join(listOf("", "/")))
  }
}

// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.endpoints

import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EndpointRequestsTest : BasePlatformTestCase() {
  fun testRequestTextUsesVariablesForPathParameters() {
    assertEquals("### GET /api/users/{id}\nGET http://localhost:9090/ctx/api/users/{{id}}\n\n" +
                 "### POST /api/users/{id}\nPOST http://localhost:9090/ctx/api/users/{{id}}\nContent-Type: application/json\n\n{\n  \n}\n\n",
                 EndpointRequests.requestText(listOf("GET", "POST"), "/api/users/{id}", "http://localhost:9090/ctx/"))
    assertEquals("/a/{{id}}/{{name}}", EndpointRequests.pathTemplate("/a/{id:\\d+}/{name}"))
    assertTrue(EndpointRequests.requestText(emptyList(), "/x", EndpointRequests.DEFAULT).startsWith("### GET /x\nGET http://localhost:8080/x\n"))
  }

  fun testServerSettingsFromEachFormat() {
    assertEquals("http://localhost:8081/api", ServerConfig.baseUrl(ServerConfig.yaml("""
      spring:
        application:
          name: shop # comment
      server:
        port: ${'$'}{PORT:8081}
        servlet:
          context-path: /api/
      ---
      server:
        port: 9999
    """.trimIndent())))
    assertEquals("https://localhost:8443", ServerConfig.baseUrl(ServerConfig.properties("server.port=8443\nserver.ssl.enabled=true")))
    assertEquals("http://localhost:8080/flux", ServerConfig.baseUrl(ServerConfig.properties("spring.webflux.base-path: /flux")))
    assertEquals("http://localhost:7070", ServerConfig.baseUrl(ServerConfig.hocon("""
      ktor {
          deployment {
              port = 7070
              port = ${'$'}{?PORT}
          }
          application { modules = [ shop.ApplicationKt.module ] }
      }
    """.trimIndent())))
    assertNull(ServerConfig.baseUrl(ServerConfig.yaml("spring:\n  profiles:\n    active: local")))
  }

  fun testBaseUrlFromTheModuleResources() {
    myFixture.addFileToProject("application.yml", "server:\n  port: 8181\n")
    assertEquals("http://localhost:8181", EndpointRequests.baseUrl(project, module))
  }

  fun testAppendsToTheScratchFile() {
    EndpointRequests.appendAndOpen(project, "GET http://localhost/a\n")
    EndpointRequests.appendAndOpen(project, "GET http://localhost/b\n")
    val file = ScratchFileService.getInstance().findFile(ScratchRootType.getInstance(), EndpointRequests.SCRATCH_NAME, ScratchFileService.Option.existing_only)!!
    try {
      assertEquals("GET http://localhost/a\n\nGET http://localhost/b\n", FileDocumentManager.getInstance().getDocument(file)!!.text)
    }
    finally {
      com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { file.delete(this) }
    }
  }
}
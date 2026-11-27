// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import dev.phantom.http.core.*
import dev.phantom.http.core.exec.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient

class HttpRunAndCacheTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.http")
    }
  }

  private var server: HttpServer? = null

  override fun tearDown() {
    try {
      server?.stop(0)
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  private fun file(text: String): HttpRequestFile = myFixture.configureByText("api.http", text) as HttpRequestFile

  private fun warnings(): List<String> = myFixture.doHighlighting(HighlightSeverity.WARNING)
    .filter { it.severity == HighlightSeverity.WARNING }.mapNotNull { it.description }

  private fun startServer(): String {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/echo") { ex ->
      val bytes = "{\"ok\":true}".toByteArray()
      ex.responseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(200, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }
    s.createContext("/drip") { ex ->
      ex.sendResponseHeaders(200, 0)
      try {
        repeat(200) {
          ex.responseBody.write('x'.code)
          ex.responseBody.flush()
          Thread.sleep(100)
        }
      }
      catch (_: Exception) {
      }
      ex.close()
    }
    s.executor = java.util.concurrent.Executors.newCachedThreadPool()
    s.start()
    server = s
    return "http://127.0.0.1:${s.address.port}"
  }

  fun testRunAllWithMoreRequestsThanTabsRunsEveryRequest() {
    val base = startServer()
    val file = file((1..12).joinToString("\n") { "### r$it\nGET $base/echo\n" })
    HttpRunner.run(project, file.virtualFile, file.requests, null)
    val toolWindow = { ToolWindowManager.getInstance(project).getToolWindow(HttpResponseToolWindow.ID) }
    val selected = { toolWindow()?.contentManager?.contents?.firstOrNull { it.displayName == "r12" } }
    try {
      PlatformTestUtil.waitWithEventsDispatching({
        "the last request did not finish: " + toolWindow()?.contentManager?.contents?.map { it.displayName } + " " +
        selected()?.displayName + " " + (selected()?.component as? HttpResponsePanel)?.shownLabels + " " +
        (selected()?.component as? HttpResponsePanel)?.shownBody
      }, {
        (selected()?.component as? HttpResponsePanel)?.shownBody != null
      }, 30)
    }
    finally {
      toolWindow()?.contentManager?.removeAllContents(true)
    }
  }

  fun testStopCancelsTheRunningRequest() {
    val base = startServer()
    val file = file("### slow\nGET $base/drip\n")
    HttpRunner.run(project, file.virtualFile, file.requests, null)
    val toolWindow = { ToolWindowManager.getInstance(project).getToolWindow(HttpResponseToolWindow.ID) }
    val panel = { toolWindow()?.contentManager?.contents?.firstOrNull { it.displayName == "slow" }?.component as? HttpResponsePanel }
    val sending = PhantomHttpBundle.message("http.response.sending", "GET $base/drip")
    try {
      PlatformTestUtil.waitWithEventsDispatching("not sending", { panel()?.shownLabels?.contains(sending) == true }, 10)
      panel()!!.job!!.cancel()
      PlatformTestUtil.waitWithEventsDispatching("Stop did not cancel", {
        panel()?.shownLabels?.contains(PhantomHttpBundle.message("http.run.cancelled")) == true
      }, 5)
    }
    finally {
      toolWindow()?.contentManager?.removeAllContents(true)
    }
  }

  fun testStopEndsASlowBodyRead() {
    val base = startServer()
    val request = HttpRequestBuilder.build(file("GET $base/drip").requests[0], HttpVariables.resolver(myFixture.file, emptyMap(), null))
    val service = HttpExecutionService.getInstance(project)
    runBlocking {
      val sending = async(Dispatchers.IO) { service.send(request) }
      delay(500)
      sending.cancel()
      // Without closing the stream the read waits for the server's 20 s body.
      withTimeout(5_000) { sending.join() }
    }
  }

  fun testRecursiveHandlerIsReportedAsAnError() {
    val request = HttpRequestModel("r", "GET", URI("http://localhost/"), HttpClient.Version.HTTP_1_1, emptyList(), emptyList(),
                                   true, true, null, null, listOf(HttpHandlerSource("function f() { return f() } f()", 1, "api.http")))
    val response = HttpResponseModel(200, HttpClient.Version.HTTP_1_1, emptyList(), ByteArray(0), false, 1, emptyList())
    val report = HttpHandlerRunner.run(request.handlers, request, response, HashMap()) { null }
    assertEquals(1, report.errors.size)
    assertTrue(report.errors[0], report.errors[0].contains("call depth"))
  }

  fun testLargeBodyIsShownAsPlainText() {
    val json = "[" + (1..200_000).joinToString(",") + "]"
    val response = HttpResponseModel(200, HttpClient.Version.HTTP_1_1, listOf("Content-Type" to "application/json"), json.toByteArray(),
                                     false, 1, emptyList())
    val body = HttpResponseFormatter.prepare(response)
    assertEquals("txt", body.extension)
    assertEquals(json, body.text)
  }

  fun testEnvironmentSwitchAndUnsavedEnvEditsUpdateWarnings() {
    val env = myFixture.addFileToProject("http-client.env.json", """{"dev": {"a": "1"}, "prod": {"b": "2"}}""")
    val file = file("GET http://localhost/{{a}}")
    val service = HttpEnvironmentService.getInstance(project)
    service.selectEnvironment(file.virtualFile, "dev")
    assertEmpty(warnings())
    service.selectEnvironment(file.virtualFile, "prod")
    assertSameElements(warnings(), "Unresolved variable 'a'")

    val document = FileDocumentManager.getInstance().getDocument(env.virtualFile)!!
    WriteCommandAction.runWriteCommandAction(project) { document.setText("""{"dev": {"a": "1"}, "prod": {"a": "3"}}""") }
    assertEmpty(warnings())
  }

  fun testAuthTokenIsNotOfferedInCompletion() {
    myFixture.addFileToProject("http-client.env.json", """{"dev": {"host": "h", "Security": {"Auths": {"kc": {"Type": "OAuth2"}}}}}""")
    file("GET {{<caret>}}")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "host")
    assertDoesntContain(myFixture.lookupElementStrings!!, "\$auth.token(\"kc\")")
  }

  fun testRenameFileVariableUpdatesReferences() {
    file("@ho<caret>st = http://localhost\nGET {{host}}/a\n")
    myFixture.renameElementAtCaret("base")
    myFixture.checkResult("@base = http://localhost\nGET {{base}}/a\n")
  }

  fun testImportOfAFileCreatedLaterResolves() {
    val file = file("import ./later.http\nGET http://localhost/{{v}}\n")
    assertEmpty(HttpImports.importedFiles(file))
    myFixture.addFileToProject("later.http", "@v = 1\n")
    assertEquals(listOf("later.http"), HttpImports.importedFiles(file).map { it.name })
  }
}

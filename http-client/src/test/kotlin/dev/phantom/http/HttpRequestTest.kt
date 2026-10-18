// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import dev.phantom.http.core.*
import dev.phantom.http.core.exec.*
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.http.HttpTimeoutException
import java.nio.file.Files

class HttpRequestTest : BasePlatformTestCase() {
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

  private fun build(block: HttpRequestBlock, env: String? = null): HttpRequestModel =
    HttpRequestBuilder.build(block, HttpVariables.resolver(block.containingFile, emptyMap(), env))

  private fun warnings(): List<String> = myFixture.doHighlighting(HighlightSeverity.WARNING)
    .filter { it.severity == HighlightSeverity.WARNING }.mapNotNull(HighlightInfo::getDescription)

  private fun startServer(): String {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/echo") { ex ->
      val body = ex.requestBody.readAllBytes().decodeToString()
      val reply = """{"method":"${ex.requestMethod}","query":"${ex.requestURI.rawQuery}","auth":"${ex.requestHeaders.getFirst("Authorization")}","length":${body.length}}"""
      ex.responseHeaders.add("Content-Type", "application/json")
      val bytes = reply.toByteArray()
      ex.sendResponseHeaders(201, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }
    s.createContext("/slow") { ex ->
      Thread.sleep(3000)
      ex.sendResponseHeaders(204, -1)
      ex.close()
    }
    s.start()
    server = s
    return "http://127.0.0.1:${s.address.port}"
  }

  fun testParsesBlocksAndBuildsRequests() {
    val file = file("""
      @host = http://localhost:8080
      @api = {{host}}/api/v1

      ### Login
      # @no-redirect
      POST {{api}}/auth/login HTTP/1.1
      Content-Type: application/json
      X-Trace: {{${'$'}uuid}}

      {
        "email": "a@b.c"
      }

      ###
      GET {{api}}/restaurants
          ?page=0
          &size=20
      Accept: application/json

      ###
      GET
    """.trimIndent())
    assertEquals(3, file.requests.size)

    val login = build(file.requests[0])
    assertEquals("Login", login.name)
    assertEquals("POST", login.method)
    assertEquals("http://localhost:8080/api/v1/auth/login", login.uri.toString())
    assertFalse(login.followRedirects)
    assertEquals("application/json", login.headers.first { it.first == "Content-Type" }.second)
    assertEquals(36, login.headers.first { it.first == "X-Trace" }.second.length)
    assertEquals(listOf(HttpRequestModel.BodyPart.Text("{\n  \"email\": \"a@b.c\"\n}")), login.body)

    val list = build(file.requests[1])
    assertEquals("GET", list.method)
    assertEquals("http://localhost:8080/api/v1/restaurants?page=0&size=20", list.uri.toString())
    assertEmpty(list.body)

    // The half-written last request does not disturb the others and fails with a message.
    assertThrows(HttpRequestBuildException::class.java) { build(file.requests[2]) }
    assertEmpty(warnings())
  }

  fun testEnvironmentsPrivateWinsAndUnknownVariablesWarn() {
    myFixture.addFileToProject("http-client.env.json", """{"dev": {"host": "http://dev", "user": "public"}, "prod": {"host": "http://prod"}, "${'$'}shared": {"ver": "v1"}}""")
    myFixture.addFileToProject("http-client.private.env.json", """{"dev": {"user": "secret"}}""")
    val file = file("GET {{host}}/{{ver}}/users/{{user}}?x={{missing}}")
    val vFile = file.virtualFile
    val service = HttpEnvironmentService.getInstance(project)
    assertSameElements(service.environmentsFor(vFile).names, "dev", "prod")
    assertSameElements(warnings(), "Unresolved variable 'missing'")

    service.selectEnvironment(vFile, "dev")
    assertThrows(HttpRequestBuildException::class.java, "missing") { build(file.requests[0], "dev") }
    myFixture.configureByText("api.http", "GET {{host}}/{{ver}}/users/{{user}}")
    val request = build((myFixture.file as HttpRequestFile).requests[0], "dev")
    assertEquals("http://dev/v1/users/secret", request.uri.toString())

    // Ctrl+B on a variable goes to the env file that defines it.
    myFixture.configureByText("api.http", "GET {{ho<caret>st}}/x")
    val target = myFixture.getReferenceAtCaretPositionWithAssertion().resolve()
    assertEquals("http-client.env.json", target?.containingFile?.name)
  }

  fun testVariableCompletion() {
    myFixture.addFileToProject("http-client.env.json", """{"local": {"host": "http://localhost", "token": "t"}}""")
    file("@base = x\nGET {{<caret>}}")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "host", "token", "base", "\$uuid", "\$timestamp")
  }

  fun testHandlerGlobalsAreNotUnresolved() {
    file("""
      POST http://localhost/login

      > {%
        client.global.set("token", response.body.token);
      %}

      ###
      GET http://localhost/me
      Authorization: Bearer {{token}}
    """.trimIndent())
    assertEmpty(warnings())
  }

  fun testRunGutterOnEachRequest() {
    file("### a\nGET http://localhost/a\n\n###\nPO<caret>ST http://localhost/b\n")
    val gutters = myFixture.findGuttersAtCaret()
    assertEquals(1, gutters.size)
    assertEquals(2, myFixture.findAllGutters().size)
  }

  fun testSendsToServer() {
    val base = startServer()
    val bodyFile = Files.createTempFile("phantom-http", ".json")
    Files.writeString(bodyFile, "{\"a\":1}")
    val file = file("""
      @base = $base
      POST {{base}}/echo
          ?q=1
      Authorization: Bearer abc
      Content-Type: application/json

      < $bodyFile

      ###
      # @timeout 1
      GET {{base}}/slow
    """.trimIndent())
    val service = HttpExecutionService.getInstance(project)
    val response = runBlocking { service.send(build(file.requests[0])) }
    assertEquals(201, response.status)
    assertEquals("application/json", response.contentType)
    val body = HttpResponseFormatter.prepare(response)
    assertEquals("json", body.extension)
    assertEquals("""
      {
          "method": "POST",
          "query": "q=1",
          "auth": "Bearer abc",
          "length": 7
      }
    """.trimIndent(), body.text)

    val timeout = build(file.requests[1])
    try {
      runBlocking { service.send(timeout) }
      fail("expected a timeout")
    }
    catch (_: HttpTimeoutException) {
    }
    Files.deleteIfExists(bodyFile)
  }

  fun testMultipartUsesCrlfAndFileParts() {
    val upload = Files.createTempFile("phantom-http", ".bin")
    Files.writeString(upload, "BIN")
    val file = file("""
      POST http://localhost/upload
      Content-Type: multipart/form-data; boundary=B

      --B
      Content-Disposition: form-data; name="request"

      {"a": 1}
      --B
      Content-Disposition: form-data; name="file"; filename="a.bin"

      < $upload
      --B--
    """.trimIndent())
    val parts = build(file.requests[0]).body
    assertEquals(3, parts.size)
    assertEquals("--B\r\nContent-Disposition: form-data; name=\"request\"\r\n\r\n{\"a\": 1}\r\n--B\r\n" +
                 "Content-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n\r\n",
                 (parts[0] as HttpRequestModel.BodyPart.Text).text)
    assertEquals(upload, (parts[1] as HttpRequestModel.BodyPart.File).path)
    assertEquals("\r\n--B--", (parts[2] as HttpRequestModel.BodyPart.Text).text)
    Files.deleteIfExists(upload)
  }

  fun testResponseHandlersSetGlobalsRunTestsAndLog() {
    val base = startServer()
    val file = file("""
      POST $base/echo?q=3
      Authorization: Bearer first

      > {%
        client.test("created", function () {
          client.assert(response.status === 201, "status " + response.status);
        });
        client.test("method", () => client.assert(response.body.method === "GET", "was " + response.body.method));
        client.global.set("token", response.body.query + "-" + response.headers.valueOf("content-type"));
        client.log("length", response.body.length, { ok: true });
      %}

      ###
      GET $base/echo
      Authorization: Bearer {{token}}
    """.trimIndent())
    val service = HttpExecutionService.getInstance(project)
    service.globals.clear()
    val first = build(file.requests[0])
    assertEquals(1, first.handlers.size)
    assertEquals(5, first.handlers[0].line)
    val response = runBlocking { service.send(first) }
    val report = HttpHandlerRunner.run(first.handlers, first, response, service.globals) { null }
    assertEquals(listOf(HttpTestResult("created", true, null), HttpTestResult("method", false, "was POST")), report.tests)
    assertEquals(listOf("length 0 {\"ok\":true}"), report.logs)
    assertEmpty(report.errors)
    assertEquals("q=3-application/json", service.globals["token"])

    val second = HttpRequestBuilder.build(file.requests[1], HttpVariables.resolver(file, service.globals, null))
    assertEquals("Bearer q=3-application/json", second.headers.single { it.first == "Authorization" }.second)

    // an error in a script names the file and line, and does not stop the request
    val broken = file("GET $base/echo\n\n> {%\nclient.log(1);\nresponse.nope.x;\n%}")
    val request = build(broken.requests[0])
    val brokenReport = HttpHandlerRunner.run(request.handlers, request, runBlocking { service.send(request) }, service.globals) { null }
    assertEquals(listOf("1"), brokenReport.logs)
    assertEquals(1, brokenReport.errors.size)
    assertTrue(brokenReport.errors[0], brokenReport.errors[0].startsWith("api.http, line 5: TypeError: Cannot read properties of undefined"))
    service.globals.clear()
  }

  fun testRunShowsResponseInToolWindow() {
    val base = startServer()
    val file = file("POST $base/echo?q=2\nAuthorization: Bearer t\n\nhello")
    HttpRunner.run(project, file.virtualFile, file.requests, null)
    val toolWindow = { ToolWindowManager.getInstance(project).getToolWindow(HttpResponseToolWindow.ID) }
    val panel = { toolWindow()?.contentManager?.selectedContent?.component as? HttpResponsePanel }
    try {
      PlatformTestUtil.waitWithEventsDispatching("no response shown", { panel()?.shownBody != null }, 20)
      assertEquals("""
        {
            "method": "POST",
            "query": "q=2",
            "auth": "Bearer t",
            "length": 5
        }
      """.trimIndent(), panel()!!.shownBody)
      assertContainsElements(panel()!!.shownLabels, "201 Created")
    }
    finally {
      toolWindow()?.contentManager?.removeAllContents(true)
    }
  }

  fun testHandlerWithoutRequestLineIsNotSentAsUrl() {
    val base = startServer()
    val file = file("> {% client.test(\"ok\", () => client.assert(response.status === 200)) %}\n\nGET $base/echo\n\n> {% client.log(response.status) %}\n")
    assertEquals(listOf("GET $base/echo"), file.requests.map { build(it).requestLine })
    assertEquals(1, build(file.requests[0]).handlers.size)
    assertContainsElements(warnings(), PhantomHttpBundle.message("http.handler.no.request"))
    val multiLine = file("> {%\nclient.log(1);\n%}\nGET $base/echo\n")
    assertEquals(listOf("GET $base/echo"), multiLine.requests.map { build(it).requestLine })
  }

  fun testRunShowsHandlerTestResults() {
    val base = startServer()
    val file = file("GET $base/echo\n\n> {% client.test(\"ok\", () => client.assert(response.status === 201)) %}\n")
    HttpRunner.run(project, file.virtualFile, file.requests, null)
    val toolWindow = { ToolWindowManager.getInstance(project).getToolWindow(HttpResponseToolWindow.ID) }
    val panel = { toolWindow()?.contentManager?.selectedContent?.component as? HttpResponsePanel }
    try {
      PlatformTestUtil.waitWithEventsDispatching("no response shown", { panel()?.shownBody != null }, 20)
      assertContainsElements(panel()!!.shownLabels, PhantomHttpBundle.message("http.handler.tests", 1, 1))
    }
    finally {
      toolWindow()?.contentManager?.removeAllContents(true)
    }
  }
}

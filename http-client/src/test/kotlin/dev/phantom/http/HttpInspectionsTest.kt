// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.http.core.*

class HttpInspectionsTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.http")
    }
  }

  private fun msg(key: String, vararg params: Any) = PhantomHttpBundle.message(key, *params)

  /** Description and highlighted text of every problem at or above weak warning, in file order. */
  private fun problems(text: String): List<Pair<String, String>> {
    myFixture.configureByText("api.http", text)
    val doc = myFixture.editor.document.text
    return myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
      .filter { it.description != null && !it.description.startsWith("Unresolved variable") }
      .sortedBy { it.startOffset }
      .map { it.description to doc.substring(it.startOffset, it.endOffset) }
  }

  fun testRestrictedHeaders() {
    myFixture.enableInspections(HttpRestrictedHeaderInspection())
    assertEquals(listOf(msg("http.inspection.restricted.header", "Content-Length") to "Content-Length",
                        msg("http.inspection.restricted.header", "host") to "host"), problems("""
      POST http://localhost/a
      Content-Type: application/json
      Content-Length: 12
      host: other
      Accept: */*

      {}
    """.trimIndent()))
  }

  fun testCustomMethodAndWhitespaceInTarget() {
    myFixture.enableInspections(HttpCustomMethodInspection(), HttpWhitespaceInTargetInspection())
    assertEquals(listOf(
      msg("http.inspection.method.custom", "FETCH", "FETCH") to "FETCH",
      msg("http.inspection.method.case", "get", "GET") to "get",
      msg("http.inspection.target.whitespace") to " ",
    ), problems("""
      FETCH http://localhost/a

      ###
      get http://localhost/b

      ###
      GET http://localhost/search?q=a b HTTP/1.1

      ###
      GET http://{{ host }}/c
          ?page=0
          &size=20

      ###
      localhost:8080/plain
    """.trimIndent()))
  }

  fun testJsonBody() {
    myFixture.enableInspections(HttpJsonBodyInspection())
    val result = problems("""
      POST http://localhost/ok
      Content-Type: application/json; charset=utf-8

      {"id": {{id}}, "name": "{{name}}", "tags": [1, 2.5e3, -0, true, null], "s": "a\"é"}

      ###
      POST http://localhost/bad
      Content-Type: application/problem+json

      {"a": 1,}

      ###
      POST http://localhost/text
      Content-Type: text/plain

      {not json

      ###
      POST http://localhost/file
      Content-Type: application/json

      < ./body.json
    """.trimIndent())
    assertEquals(listOf(msg("http.inspection.json.body", "Trailing comma") to "}"), result)
  }

  fun testMissingSeparator() {
    myFixture.enableInspections(HttpMissingSeparatorInspection())
    assertEquals(listOf(msg("http.inspection.missing.separator") to "GET http://localhost/next"), problems("""
      POST http://localhost/a
      Content-Type: text/plain

      hello
      GET http://localhost/next
    """.trimIndent()))
  }

  fun testPlaceholders() {
    myFixture.enableInspections(HttpPlaceholderInspection())
    assertEquals(listOf(msg("http.inspection.placeholder.empty") to "{{}}",
                        msg("http.inspection.placeholder.unclosed", "{{", "}}") to "{{"), problems("""
      @host = http://localhost
      GET {{host}}/a/{{}}
      Authorization: Bearer {{token
    """.trimIndent()))
  }

  fun testHeaders() {
    myFixture.enableInspections(HttpInvalidHeaderInspection(), HttpUnknownHeaderInspection())
    assertEquals(listOf(
      msg("http.inspection.header.unknown", "Content-Tyep") to "Content-Tyep",
      msg("http.inspection.header.invalid.name", "Bad Name") to "Bad Name",
      msg("http.inspection.header.no.colon") to "plain text",
      msg("http.inspection.header.invalid.name", "{\"a\"") to "{\"a\"",
    ), problems("""
      POST http://localhost/a
      Content-Tyep: application/json
      Accept: */*
      X-Request-Id: 1
      Idempotency-Key: k
      Bad Name: v
      plain text
      {"a": 1}
    """.trimIndent()))
  }

  fun testInsecureUrls() {
    myFixture.enableInspections(HttpInsecureUrlInspection())
    assertEquals(listOf(msg("http.inspection.insecure.url", "api.example.com") to "http://",
                        msg("http.inspection.insecure.url", "staging.example.org") to "http://"), problems("""
      @remote = http://api.example.com
      @local = http://localhost:8080
      @docker = http://mysql:3306

      GET http://staging.example.org/a

      ###
      GET https://secure.example.com/b

      ###
      GET http://192.168.0.10/c

      ###
      GET http://{{host}}/d
    """.trimIndent()))
  }

  fun testJsonCheck() {
    assertNull(JsonCheck.firstError("""  {"a": [1, {"b": "c"}], "d": -1.5E+2}  """))
    assertEquals("Unexpected text after the JSON value", JsonCheck.firstError("{} {}")?.second)
    assertEquals("Property name in double quotes expected", JsonCheck.firstError("{a: 1}")?.second)
    assertEquals("Unclosed string", JsonCheck.firstError("{\"a")?.second)
    assertEquals("Trailing comma", JsonCheck.firstError("[1,]")?.second)
    assertEquals("Invalid number", JsonCheck.firstError("[-x]")?.second)
  }

  fun testXmlBodyFollowedByRequestNeedsSeparator() {
    myFixture.enableInspections(HttpMissingSeparatorInspection())
    assertEquals(listOf(msg("http.inspection.missing.separator") to "GET http://localhost/b"), problems("""
      POST http://localhost/a
      Content-Type: application/xml

      <order><id>1</id></order>
      GET http://localhost/b
    """.trimIndent()))
  }

  fun testAuthIds() {
    myFixture.enableInspections(HttpUnresolvedAuthIdInspection())
    myFixture.addFileToProject("http-client.env.json", """
      {"dev": {"host": "http://localhost", "Security": {"Auths": {"keycloak": {"Type": "OAuth2", "Grant Type": "Client Credentials",
        "Client ID": "app", "Token URL": "http://localhost/token"}}}}}
    """.trimIndent())
    assertEquals(listOf(msg("http.inspection.auth.unknown", "keyclaok") to "{{${'$'}auth.token(\"keyclaok\")}}"), problems("""
      GET {{host}}/a
      Authorization: Bearer {{${'$'}auth.token("keycloak")}}

      ###
      GET {{host}}/b
      Authorization: Bearer {{${'$'}auth.token("keyclaok")}}
    """.trimIndent()))
    // the auth reference is neither an unresolved variable nor a dead end for Ctrl+B
    myFixture.configureByText("b.http", "GET http://localhost\nAuthorization: Bearer {{${'$'}auth.token(\"key<caret>cloak\")}}\n")
    assertEmpty(myFixture.doHighlighting(HighlightSeverity.WARNING).filter { it.description?.startsWith("Unresolved") == true })
    val ref = myFixture.file.findReferenceAt(myFixture.caretOffset)!!
    assertEquals("http-client.env.json", ref.resolve()?.containingFile?.name)
  }

  fun testProtocolAndEncoding() {
    myFixture.enableInspections(HttpProtocolInspection(), HttpAmbiguousEncodingInspection())
    assertEquals(listOf(
      msg("http.inspection.protocol.websocket", "ws") to "ws",
      msg("http.inspection.protocol.unknown", "ftp") to "ftp",
      msg("http.inspection.encoding.char", "|", "%7C") to "|",
      msg("http.inspection.encoding.percent") to "%",
    ), problems("""
      GET ws://localhost/socket

      ###
      GET ftp://files.local/a

      ###
      GET http://localhost/search?q=a|b&ok=%20&{{q}}

      ###
      GET http://localhost/rate?p=50%
    """.trimIndent()))
  }

  fun testAuthConfiguration() {
    myFixture.enableInspections(HttpAuthConfigurationInspection())
    myFixture.configureByText("http-client.env.json", """
      {
        "dev": {"Security": {"Auths": {
          "ok": {"Type": "OAuth2", "Grant Type": "Authorization Code", "Client ID": "{{clientId}}", "Auth URL": "a", "Token URL": "t"},
          "device": {"Type": "OAuth2", "Grant Type": "Device Authorization", "Client ID": "c", "Token URL": "t"},
          "typo": {"Type": "OAuth2", "Grant Type": "Client Credential", "Client ID": "c"},
          "untyped": {"Client ID": "c"}
        }}}
      }
    """.trimIndent())
    val found = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).mapNotNull { it.description }.sorted()
    assertEquals(listOf(
      msg("http.inspection.auth.missing.for", "Device Auth URL", "Device Authorization"),
      msg("http.inspection.auth.missing", "Type"),
      msg("http.inspection.auth.grant", "Client Credential", "Authorization Code, Client Credentials, Device Authorization, Implicit, Password"),
    ).sorted(), found)
  }

  fun testGhostModeTurnsOffTheChecks() {
    ghostMode(true)
    myFixture.enableInspections(HttpRestrictedHeaderInspection())
    assertEmpty(problems("""
      POST http://localhost/a
      Content-Length: 12

      {}
    """.trimIndent()))
  }

  // Phantom registers phantom.ghost; the IntelliJ test platform does not, so a system property, which RegistryValue reads before
  // the bundle, makes the key resolvable for setValue.
  private fun ghostMode(on: Boolean) {
    System.setProperty(GhostMode.KEY, "false")
    Disposer.register(testRootDisposable) { System.clearProperty(GhostMode.KEY) }
    Registry.get(GhostMode.KEY).setValue(on, testRootDisposable)
  }
}

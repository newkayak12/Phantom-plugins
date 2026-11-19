// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.hooks.HookEnv
import dev.phantom.ai.hooks.HookServer
import java.net.HttpURLConnection
import java.net.URI

class HookServerInIdeTest : BasePlatformTestCase() {
  private val wt = "/tmp/phantom-hook-test-wt"
  private lateinit var server: HookServer

  override fun setUp() {
    super.setUp()
    server = HookServer.getInstance().also { it.ensureStarted() }
    val store = SessionStore.getInstance()
    store.put(AgentSession("tabA", wt, AgentKind.CLAUDE))
    store.put(AgentSession("tabB", wt, AgentKind.CLAUDE))
    store.put(AgentSession("tabC", wt, AgentKind.CODEX))
  }

  override fun tearDown() {
    try { listOf("tabA", "tabB", "tabC").forEach { SessionStore.getInstance().remove(it) } } finally { super.tearDown() }
  }

  private fun call(method: String, path: String, body: String = "", token: String? = server.token, tab: String? = null): Int {
    val c = URI("http://127.0.0.1:${server.port}$path").toURL().openConnection() as HttpURLConnection
    c.requestMethod = method
    if (token != null) c.setRequestProperty(HookEnv.TOKEN_HEADER, token)
    if (tab != null) c.setRequestProperty("X-Phantom-Tab-Id", tab)
    if (method == "POST") { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
    return c.responseCode.also { c.disconnect() }
  }

  private fun ev(name: String, extra: String = "") = """{"hook_event_name":"$name","session_id":"sess-1"$extra}"""

  fun testPostChangesStatusAndStoresSession() {
    assertEquals(204, call("POST", "/hook/claude", ev("UserPromptSubmit", ""","prompt":"first""""), tab = "tabA"))
    val s = SessionStore.getInstance().get("tabA")!!
    assertEquals(AgentStatus.WORKING, s.status); assertEquals("first", s.firstPrompt); assertEquals("sess-1", s.sessionId)
    assertTrue(AiState.getInstance().sessionsOf(wt).any { it.sessionId == "sess-1" && it.provider == "CLAUDE" })
    call("POST", "/hook/claude", ev("Stop"), tab = "tabA")
    val d = SessionStore.getInstance().get("tabA")!!
    assertEquals(AgentStatus.DONE, d.status); assertTrue(d.unread); assertEquals("first", d.firstPrompt)
  }

  fun testUnloadingRefusesHooksUnread() {
    dev.phantom.ai.core.Unloading.start()
    try {
      assertEquals(503, call("POST", "/hook/claude", ev("UserPromptSubmit"), tab = "tabA"))
      assertNull(SessionStore.getInstance().get("tabA")!!.status)
    } finally {
      dev.phantom.ai.core.Unloading.reset()
    }
  }

  fun testTokenRequired() {
    assertEquals(403, call("POST", "/hook/claude", ev("Stop"), token = null, tab = "tabA"))
    assertEquals(403, call("POST", "/hook/claude", ev("Stop"), token = "wrong", tab = "tabA"))
    assertNull(SessionStore.getInstance().get("tabA")!!.status)
  }

  fun testGetIs405() = assertEquals(405, call("GET", "/hook/claude"))

  fun testEventsUpdateOnlyMatchingTab() {
    call("POST", "/hook/claude", ev("PermissionRequest"), tab = "tabB")
    assertEquals(AgentStatus.WAITING, SessionStore.getInstance().get("tabB")!!.status)
    assertNull(SessionStore.getInstance().get("tabA")!!.status)
    assertEquals(2, SessionStore.getInstance().forWorktree(wt).count { it.kind == AgentKind.CLAUDE })
  }

  fun testSessionWithoutEventsStaysLaunched() = assertNull(SessionStore.getInstance().get("tabC")!!.status)

  fun testCodexRoute() {
    call("POST", "/hook/codex", ev("PreToolUse"), tab = "tabC")
    assertEquals(AgentStatus.WORKING, SessionStore.getInstance().get("tabC")!!.status)
  }

  fun testStatuslineDelivered() {
    val got = java.util.concurrent.atomic.AtomicReference<Pair<String?, String>>()
    val l = dev.phantom.ai.hooks.StatuslineListener { t, b -> got.set(t to b) }
    server.statuslineListeners.add(l)
    try { assertEquals(204, call("POST", "/statusline/claude", """{"x":1}""", tab = "tabA")) } finally { server.statuslineListeners.remove(l) }
    assertEquals("tabA" to """{"x":1}""", got.get())
  }

  private fun mcpCall(body: String, headers: Map<String, String>): Pair<Int, String> {
    // java.net.http: HttpURLConnection drops a restricted Origin header
    val request = java.net.http.HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/mcp?tabId=tabA"))
      .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
      .apply { headers.forEach { (k, v) -> header(k, v) } }
      .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build()
    val response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
    return response.statusCode() to response.body()
  }

  fun testMcpTakesTheBearerTokenAndAnswersJsonWithTheTabsWorktree() {
    val seen = java.util.concurrent.atomic.AtomicReference<String?>()
    val before = server.mcp
    server.mcp = dev.phantom.ai.mcp.PhantomMcp(object : dev.phantom.ai.mcp.EditorBridge {
      override fun open(worktree: String?, path: String, line: Int?, column: Int?, endLine: Int?) = path.also { seen.set(worktree) }
      override fun context(worktree: String?) = com.google.gson.JsonObject()
      override fun runOutput(worktree: String?, name: String?, lines: Int, grep: String?) = ""
    }, dev.phantom.ai.mcp.IdeCodeIndex()) { tab -> tab?.let { SessionStore.getInstance().get(it)?.worktree } }
    try {
      val bearer = mapOf("Authorization" to "Bearer ${server.token}")
      val (code, body) = mcpCall("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"open_in_editor","arguments":{"path":"a.kt"}}}""", bearer)
      assertEquals(200, code)
      assertTrue(body, body.contains("\"id\":7") && body.contains("Opened a.kt"))
      assertEquals(wt, seen.get())
      assertEquals(202, mcpCall("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", bearer).first)
      assertEquals(403, mcpCall("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", mapOf("Authorization" to "Bearer nope")).first)
      assertEquals(403, mcpCall("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", bearer + ("Origin" to "http://evil.example")).first)
      assertEquals(200, mcpCall("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", bearer + ("Origin" to "http://localhost:3000")).first)
      assertEquals(405, call("GET", "/mcp"))
    } finally {
      server.mcp = before
    }
  }

  fun testEnvAndLoopback() {
    val env = HookEnv.forTab(wt, "tabA")
    assertEquals(server.port.toString(), env[HookEnv.PORT]); assertEquals(server.token, env[HookEnv.TOKEN])
    assertEquals(wt, env[HookEnv.WORKTREE]); assertEquals("tabA", env[HookEnv.TAB_ID])
    assertEquals(32, java.util.Base64.getUrlDecoder().decode(server.token).size)
  }
}

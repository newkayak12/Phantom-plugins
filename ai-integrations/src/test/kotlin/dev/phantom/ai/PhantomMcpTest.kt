// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.phantom.ai.mcp.EditorBridge
import dev.phantom.ai.mcp.McpToolError
import dev.phantom.ai.mcp.PhantomMcp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhantomMcpTest {
  private val opened = mutableListOf<List<Any?>>()
  private val bridge = object : EditorBridge {
    override fun open(worktree: String?, path: String, line: Int?, column: Int?, endLine: Int?): String {
      if (path == "missing") throw McpToolError("No such file: missing")
      opened += listOf(worktree, path, line, column, endLine)
      return "/abs/$path"
    }
    override fun context(worktree: String?) = JsonObject().apply { addProperty("file", "/abs/a.kt"); addProperty("line", 3) }
  }
  private val mcp = PhantomMcp(bridge) { tab -> if (tab == "t1") "/w/repo" else null }

  private fun rpc(method: String, params: String = "{}", id: Int = 1, tab: String? = "t1") =
    JsonParser.parseString(mcp.handle("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""", tab)!!).asJsonObject

  @Test fun initializeAnswersTheAskedVersionAndOffersTools() {
    val r = rpc("initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"claude","version":"1"}}""")
      .getAsJsonObject("result")
    assertEquals("2025-06-18", r.get("protocolVersion").asString)
    assertTrue(r.getAsJsonObject("capabilities").has("tools"))
    assertEquals("phantom", r.getAsJsonObject("serverInfo").get("name").asString)
  }

  @Test fun unknownVersionGetsTheNewest() =
    assertEquals(PhantomMcp.VERSIONS.first(), rpc("initialize", """{"protocolVersion":"1999-01-01"}""").getAsJsonObject("result").get("protocolVersion").asString)

  @Test fun notificationsGetNoReply() {
    assertNull(mcp.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", "t1"))
    assertNull(mcp.handle("""[{"jsonrpc":"2.0","method":"notifications/initialized"}]""", "t1"))
  }

  @Test fun listsBothTools() {
    val tools = rpc("tools/list").getAsJsonObject("result").getAsJsonArray("tools").map { it.asJsonObject }
    assertEquals(listOf(PhantomMcp.OPEN, PhantomMcp.CONTEXT), tools.map { it.get("name").asString })
    assertEquals("path", tools[0].getAsJsonObject("inputSchema").getAsJsonArray("required").single().asString)
  }

  @Test fun openGoesToTheBridgeWithTheTabsWorktree() {
    val r = rpc("tools/call", """{"name":"open_in_editor","arguments":{"path":"src/a.kt","line":12,"column":"4","end_line":20}}""")
      .getAsJsonObject("result")
    assertFalse(r.get("isError").asBoolean)
    assertEquals(listOf("/w/repo", "src/a.kt", 12, 4, 20), opened.single())
    assertEquals("Opened /abs/src/a.kt at line 12.", r.getAsJsonArray("content")[0].asJsonObject.get("text").asString)
  }

  @Test fun toolFailuresAreToolErrorsNotProtocolErrors() {
    val r = rpc("tools/call", """{"name":"open_in_editor","arguments":{"path":"missing"}}""").getAsJsonObject("result")
    assertTrue(r.get("isError").asBoolean)
    assertEquals("No such file: missing", r.getAsJsonArray("content")[0].asJsonObject.get("text").asString)
    assertTrue(rpc("tools/call", """{"name":"open_in_editor","arguments":{}}""").getAsJsonObject("result").get("isError").asBoolean)
    assertTrue(rpc("tools/call", """{"name":"nope"}""").getAsJsonObject("result").get("isError").asBoolean)
  }

  @Test fun contextIsReturnedAsJsonText() {
    val text = rpc("tools/call", """{"name":"get_editor_context"}""").getAsJsonObject("result")
      .getAsJsonArray("content")[0].asJsonObject.get("text").asString
    assertEquals(3, JsonParser.parseString(text).asJsonObject.get("line").asInt)
  }

  @Test fun protocolErrors() {
    assertEquals(-32601, rpc("resources/list").getAsJsonObject("error").get("code").asInt)
    assertEquals(-32700, JsonParser.parseString(mcp.handle("{not json", null)!!).asJsonObject.getAsJsonObject("error").get("code").asInt)
    assertEquals(JsonObject(), rpc("ping").getAsJsonObject("result"))
  }

  @Test fun batchGetsOneReplyPerRequest() {
    val replies = JsonParser.parseString(mcp.handle(
      """[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"},{"jsonrpc":"2.0","id":2,"method":"tools/list"}]""",
      "t1",
    )!!).asJsonArray
    assertEquals(listOf(1, 2), replies.map { it.asJsonObject.get("id").asInt })
  }
}

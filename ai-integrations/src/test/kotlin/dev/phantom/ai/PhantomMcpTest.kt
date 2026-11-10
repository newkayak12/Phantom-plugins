// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.phantom.ai.mcp.CodeIndex
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
  private val asked = mutableListOf<List<Any?>>()
  private val index = object : CodeIndex {
    override fun findSymbol(worktree: String?, query: String, limit: Int): String { asked += listOf("symbol", worktree, query, limit); return "a.kt:1  class A" }
    override fun findFile(worktree: String?, query: String, limit: Int): String { asked += listOf("file", worktree, query, limit); return "a.kt" }
    override fun findUsages(worktree: String?, path: String, line: Int, name: String, limit: Int): String { asked += listOf("usages", worktree, path, line, name, limit); return "b.kt:2  A()" }
    override fun fileOutline(worktree: String?, path: String): String { if (path == "busy") throw McpToolError("Phantom is still indexing"); asked += listOf("outline", worktree, path); return "1  A" }
    override fun symbolInfo(worktree: String?, path: String, line: Int, name: String): String { asked += listOf("info", worktree, path, line, name); return "a.kt:1  class A" }
    override fun calls(worktree: String?, path: String, line: Int, name: String, limit: Int): String { asked += listOf("calls", worktree, path, line, name, limit); return "Called from:" }
    override fun fileProblems(worktree: String?, path: String, warnings: Boolean): String { asked += listOf("problems", worktree, path, warnings); return "No errors in a.kt." }
    override fun rename(worktree: String?, path: String, line: Int, name: String, newName: String): String { asked += listOf("rename", worktree, path, line, name, newName); return "Renamed" }
  }
  private val mcp = PhantomMcp(bridge, index) { tab -> if (tab == "t1") "/w/repo" else null }

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
    assertEquals(listOf(PhantomMcp.OPEN, PhantomMcp.CONTEXT) + INDEX_TOOLS + PhantomMcp.RENAME, tools.map { it.get("name").asString })
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

  private fun callTool(name: String, args: String) =
    rpc("tools/call", """{"name":"$name","arguments":$args}""").getAsJsonObject("result")

  private fun textOf(result: JsonObject) = result.getAsJsonArray("content")[0].asJsonObject.get("text").asString

  @Test fun indexToolsAreReadOnlyAndAskTheCallersWorkspace() {
    val tools = rpc("tools/list").getAsJsonObject("result").getAsJsonArray("tools").map { it.asJsonObject }.filter { it.get("name").asString in INDEX_TOOLS }
    assertEquals(INDEX_TOOLS.toSet(), tools.map { it.get("name").asString }.toSet())
    assertTrue(tools.all { it.getAsJsonObject("annotations").get("readOnlyHint").asBoolean })

    assertEquals("a.kt:1  class A", textOf(callTool("find_symbol", """{"query":"A"}""")))
    assertEquals("a.kt", textOf(callTool("find_file", """{"query":"a","limit":5}""")))
    assertEquals("b.kt:2  A()", textOf(callTool("find_usages", """{"path":"a.kt","line":"1","name":"A","limit":9999}""")))
    assertEquals("1  A", textOf(callTool("file_outline", """{"path":"a.kt"}""")))
    assertEquals(
      listOf(listOf("symbol", "/w/repo", "A", 30), listOf("file", "/w/repo", "a", 5), listOf("usages", "/w/repo", "a.kt", 1, "A", 200), listOf("outline", "/w/repo", "a.kt")),
      asked,
    )
  }

  @Test fun indexToolErrorsTellTheAgent() {
    val busy = callTool("file_outline", """{"path":"busy"}""")
    assertTrue(busy.get("isError").asBoolean)
    assertEquals("Phantom is still indexing", textOf(busy))
    assertEquals("line is required", textOf(callTool("find_usages", """{"path":"a.kt","name":"A"}""")))
  }

  @Test fun codeToolsTakeTheSymbolWhereItIsWrittenAndOnlyRenameWrites() {
    val tools = rpc("tools/list").getAsJsonObject("result").getAsJsonArray("tools").map { it.asJsonObject }.associateBy { it.get("name").asString }
    assertFalse(tools.getValue("rename_refactoring").getAsJsonObject("annotations").get("readOnlyHint").asBoolean)
    assertTrue(listOf("get_symbol_info", "analyze_calls", "get_file_problems").all { tools.getValue(it).getAsJsonObject("annotations").get("readOnlyHint").asBoolean })

    callTool("get_symbol_info", """{"path":"a.kt","line":1,"name":"A"}""")
    callTool("analyze_calls", """{"path":"a.kt","line":"2","name":"run"}""")
    callTool("get_file_problems", """{"path":"a.kt"}""")
    callTool("get_file_problems", """{"path":"a.kt","warnings":true}""")
    assertEquals("Renamed", textOf(callTool("rename_refactoring", """{"path":"a.kt","line":1,"name":"A","new_name":"B"}""")))
    assertEquals(
      listOf(
        listOf("info", "/w/repo", "a.kt", 1, "A"), listOf("calls", "/w/repo", "a.kt", 2, "run", 30),
        listOf("problems", "/w/repo", "a.kt", false), listOf("problems", "/w/repo", "a.kt", true), listOf("rename", "/w/repo", "a.kt", 1, "A", "B"),
      ),
      asked,
    )
    assertEquals("new_name is required", textOf(callTool("rename_refactoring", """{"path":"a.kt","line":1,"name":"A"}""")))
  }

  private companion object {
    val INDEX_TOOLS = listOf("find_symbol", "find_file", "find_usages", "file_outline", "get_symbol_info", "analyze_calls", "get_file_problems")
  }
}

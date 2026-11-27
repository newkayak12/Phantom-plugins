// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.phantom.ai.mcp.CodeIndex
import dev.phantom.ai.mcp.EditorBridge
import dev.phantom.ai.mcp.IndexAgentTools
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
    override fun runOutput(worktree: String?, name: String?, lines: Int, grep: String?): String { opened += listOf("run", worktree, name, lines, grep); return "[App: running] 1 lines" }
  }
  private val asked = mutableListOf<List<Any?>>()
  private val index = object : CodeIndex {
    override fun findFile(worktree: String?, query: String, limit: Int): String { asked += listOf("file", worktree, query, limit); return "a.kt" }
    override fun symbolInfo(worktree: String?, path: String, line: Int, name: String): String { asked += listOf("info", worktree, path, line, name); return "a.kt:1  class A" }
    override fun calls(worktree: String?, path: String, line: Int, name: String, limit: Int): String { asked += listOf("calls", worktree, path, line, name, limit); return "Called from:" }
    override fun rename(worktree: String?, path: String, line: Int, name: String, newName: String): String { asked += listOf("rename", worktree, path, line, name, newName); return "Renamed" }
    override fun readSymbol(worktree: String?, path: String, line: Int?, name: String): String { asked += listOf("read", worktree, path, line, name); return "a.kt:1-3" }
    override fun replaceSymbol(worktree: String?, path: String, line: Int?, name: String, code: String): String { asked += listOf("replace", worktree, path, line, name, code); return "Replaced" }
    override fun insertCode(worktree: String?, path: String, line: Int?, name: String, after: Boolean, code: String): String { asked += listOf("insert", worktree, path, line, name, after, code); return "Inserted" }
  }
  private val mcp = PhantomMcp(bridge, index, { IndexAgentTools().tools() }) { tab -> if (tab == "t1") "/w/repo" else null }

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
    assertEquals(listOf(PhantomMcp.OPEN, PhantomMcp.CONTEXT) + INDEX_TOOLS + PhantomMcp.RENAME + EDIT_TOOLS + LSI_TOOLS, tools.map { it.get("name").asString })
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

    assertEquals("a.kt", textOf(callTool("find_file", """{"query":"a","limit":5}""")))
    assertEquals(listOf(listOf("file", "/w/repo", "a", 5)), asked)
  }

  @Test fun localSharedIndexToolsAreListedOnceReadOnlyWithTheirSchema() {
    val tools = rpc("tools/list").getAsJsonObject("result").getAsJsonArray("tools").map { it.asJsonObject }
    assertEquals(tools.size, tools.map { it.get("name").asString }.toSet().size)
    val lsi = tools.filter { it.get("name").asString in LSI_TOOLS }
    assertTrue(lsi.all { it.getAsJsonObject("annotations").get("readOnlyHint").asBoolean })
    val search = lsi.single { it.get("name").asString == "search_text" }.getAsJsonObject("inputSchema")
    assertEquals("text", search.getAsJsonArray("required").single().asString)
    assertEquals("boolean", search.getAsJsonObject("properties").getAsJsonObject("regex").get("type").asString)
  }

  @Test fun localSharedIndexErrorsTellTheAgent() {
    val error = rpc("tools/call", """{"name":"find_symbol","arguments":{"query":"A"}}""", tab = "t2").getAsJsonObject("result")
    assertTrue(error.get("isError").asBoolean)
    assertEquals("The IDE index is not available for this terminal; use your own search.", textOf(error))
    assertEquals("line is required", textOf(callTool("find_usages", """{"path":"a.kt","name":"A"}""")))
  }

  @Test fun codeToolsTakeTheSymbolWhereItIsWrittenAndOnlyRenameWrites() {
    val tools = rpc("tools/list").getAsJsonObject("result").getAsJsonArray("tools").map { it.asJsonObject }.associateBy { it.get("name").asString }
    assertFalse(tools.getValue("rename_refactoring").getAsJsonObject("annotations").get("readOnlyHint").asBoolean)
    assertTrue(listOf("get_symbol_info", "analyze_calls").all { tools.getValue(it).getAsJsonObject("annotations").get("readOnlyHint").asBoolean })

    callTool("get_symbol_info", """{"path":"a.kt","line":1,"name":"A"}""")
    callTool("analyze_calls", """{"path":"a.kt","line":"2","name":"run"}""")
    assertEquals("Renamed", textOf(callTool("rename_refactoring", """{"path":"a.kt","line":1,"name":"A","new_name":"B"}""")))
    assertEquals(
      listOf(
        listOf("info", "/w/repo", "a.kt", 1, "A"), listOf("calls", "/w/repo", "a.kt", 2, "run", 30),
        listOf("rename", "/w/repo", "a.kt", 1, "A", "B"),
      ),
      asked,
    )
    assertEquals("new_name is required", textOf(callTool("rename_refactoring", """{"path":"a.kt","line":1,"name":"A"}""")))
  }

  @Test fun symbolToolsReadAndWriteOneDeclaration() {
    val tools = rpc("tools/list").getAsJsonObject("result").getAsJsonArray("tools").map { it.asJsonObject }.associateBy { it.get("name").asString }
    assertTrue(listOf("read_symbol", "get_run_output").all { tools.getValue(it).getAsJsonObject("annotations").get("readOnlyHint").asBoolean })
    assertFalse(listOf("replace_symbol", "insert_code").any { tools.getValue(it).getAsJsonObject("annotations").get("readOnlyHint").asBoolean })

    assertEquals("a.kt:1-3", textOf(callTool("read_symbol", """{"path":"a.kt","name":"A.run"}""")))
    callTool("replace_symbol", """{"path":"a.kt","name":"run","line":"4","code":"fun run() {\n\n}"}""")
    callTool("insert_code", """{"path":"a.kt","name":"run","code":"fun b() {}"}""")
    callTool("insert_code", """{"path":"a.kt","name":"run","position":"before","code":"fun c() {}"}""")
    assertEquals(
      listOf(
        listOf("read", "/w/repo", "a.kt", null, "A.run"), listOf("replace", "/w/repo", "a.kt", 4, "run", "fun run() {\n\n}"),
        listOf("insert", "/w/repo", "a.kt", null, "run", true, "fun b() {}"), listOf("insert", "/w/repo", "a.kt", null, "run", false, "fun c() {}"),
      ),
      asked,
    )
    assertEquals("code is required", textOf(callTool("replace_symbol", """{"path":"a.kt","name":"run"}""")))
    assertEquals("position is before or after", textOf(callTool("insert_code", """{"path":"a.kt","name":"run","position":"inside","code":"x"}""")))

    assertEquals("[App: running] 1 lines", textOf(callTool("get_run_output", """{"grep":"ERROR","lines":99999}""")))
    callTool("get_run_output", """{"name":"App"}""")
    assertEquals(listOf(listOf("run", "/w/repo", null, 2000, "ERROR"), listOf("run", "/w/repo", "App", 100, null)), opened)
  }

  private companion object {
    val EDIT_TOOLS = listOf("read_symbol", "replace_symbol", "insert_code", "get_run_output")
    val INDEX_TOOLS = listOf("find_file", "get_symbol_info", "analyze_calls")
    val LSI_TOOLS = listOf("find_symbol", "find_usages", "search_text", "file_outline", "get_file_problems")
  }
}

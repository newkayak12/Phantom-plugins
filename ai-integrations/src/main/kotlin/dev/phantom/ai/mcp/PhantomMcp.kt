// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.phantom.ai.core.rethrowIfCancellation

/** A tool call that cannot be done (no such file, no editor); its message goes back to the agent as the tool's error. */
class McpToolError(message: String) : Exception(message)

/** What the tools do in the IDE. Fakeable; the real one is [IdeEditorBridge]. Called off the EDT. */
interface EditorBridge {
  /**
   * Opens [path] (absolute, or relative to [worktree]) in the editor at 1-based [line] and [column], selects through
   * [endLine], focuses it; returns the file's absolute path.
   */
  fun open(worktree: String?, path: String, line: Int?, column: Int?, endLine: Int?): String

  /** The editor the user is looking at: file, caret, selection, the other open files. */
  fun context(worktree: String?): JsonObject
}

/**
 * Phantom's MCP server for its own agent terminals (JSON-RPC over the hook server's `/mcp`, streamable HTTP answered with
 * plain JSON, no SSE): lets Claude or Codex show the user code in the editor. [worktreeOf] maps the calling tab to its
 * worktree, the base of relative paths and the project to open in. Pure apart from [bridge].
 */
class PhantomMcp(private val bridge: EditorBridge, private val worktreeOf: (String?) -> String?) {
  /** The JSON reply to [body], or null when it held only notifications (HTTP 202, no body). */
  fun handle(body: String, tabId: String?): String? {
    val request = try {
      JsonParser.parseString(body)
    } catch (e: Exception) {
      rethrowIfCancellation(e)
      return error(JsonNull.INSTANCE, PARSE_ERROR, "Parse error").toString()
    }
    if (request.isJsonArray) {
      val replies = JsonArray()
      request.asJsonArray.forEach { one(it, tabId)?.let(replies::add) }
      return if (replies.isEmpty) null else replies.toString()
    }
    return one(request, tabId)?.toString()
  }

  private fun one(message: JsonElement, tabId: String?): JsonObject? {
    if (!message.isJsonObject) return error(JsonNull.INSTANCE, INVALID_REQUEST, "Invalid request")
    val m = message.asJsonObject
    val id = m.get("id")
    val method = m.get("method")?.takeIf { it.isJsonPrimitive }?.asString
    // a notification (no id) or a client's reply to us: nothing to answer
    if (id == null || id.isJsonNull) return null
    val params = m.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    return when (method) {
      "initialize" -> result(id, initialize(params))
      "ping" -> result(id, JsonObject())
      "tools/list" -> result(id, JsonObject().apply { add("tools", tools()) })
      "tools/call" -> result(id, call(params, tabId))
      null -> error(id, INVALID_REQUEST, "Invalid request")
      else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
    }
  }

  private fun initialize(params: JsonObject): JsonObject {
    val asked = params.get("protocolVersion")?.takeIf { it.isJsonPrimitive }?.asString
    return JsonObject().apply {
      addProperty("protocolVersion", asked?.takeIf { it in VERSIONS } ?: VERSIONS.first())
      add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
      add("serverInfo", JsonObject().apply { addProperty("name", "phantom"); addProperty("version", "1") })
      addProperty("instructions", INSTRUCTIONS)
    }
  }

  private fun call(params: JsonObject, tabId: String?): JsonObject {
    val name = params.get("name")?.takeIf { it.isJsonPrimitive }?.asString
    val args = params.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    val worktree = worktreeOf(tabId)
    return try {
      when (name) {
        OPEN -> {
          val path = string(args, "path") ?: throw McpToolError("path is required")
          val line = int(args, "line")
          val opened = bridge.open(worktree, path, line, int(args, "column"), int(args, "end_line"))
          text(if (line != null) "Opened $opened at line $line." else "Opened $opened.")
        }
        CONTEXT -> text(bridge.context(worktree).toString())
        else -> text("Unknown tool: $name", isError = true)
      }
    } catch (e: McpToolError) {
      text(e.message ?: "Failed", isError = true)
    } catch (e: Exception) {
      rethrowIfCancellation(e)
      text("Phantom could not do that: ${e.message ?: e.javaClass.simpleName}", isError = true)
    }
  }

  private fun tools(): JsonArray = JsonArray().apply {
    add(tool(OPEN, OPEN_DESCRIPTION, JsonObject().apply {
      add("path", prop("string", "File path, absolute or relative to your working directory."))
      add("line", prop("integer", "1-based line to put the caret on."))
      add("column", prop("integer", "1-based column on that line."))
      add("end_line", prop("integer", "Last line (1-based, inclusive) to select from `line`, to point at a block."))
    }, required = listOf("path")))
    add(tool(CONTEXT, CONTEXT_DESCRIPTION, JsonObject(), required = emptyList(), readOnly = true))
  }

  private fun tool(name: String, description: String, properties: JsonObject, required: List<String>, readOnly: Boolean = false) =
    JsonObject().apply {
      addProperty("name", name)
      addProperty("description", description)
      add("inputSchema", JsonObject().apply {
        addProperty("type", "object")
        add("properties", properties)
        add("required", JsonArray().apply { required.forEach(::add) })
      })
      add("annotations", JsonObject().apply { addProperty("readOnlyHint", readOnly); addProperty("openWorldHint", false) })
    }

  private fun prop(type: String, description: String) =
    JsonObject().apply { addProperty("type", type); addProperty("description", description) }

  private fun string(args: JsonObject, key: String): String? =
    args.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

  /** An integer argument; agents sometimes send numbers as strings. */
  private fun int(args: JsonObject, key: String): Int? {
    val v = args.get(key)?.takeIf { it.isJsonPrimitive } ?: return null
    return v.asString.trim().toDoubleOrNull()?.toInt()
  }

  private fun text(text: String, isError: Boolean = false) = JsonObject().apply {
    add("content", JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text) }) })
    addProperty("isError", isError)
  }

  private fun result(id: JsonElement, result: JsonObject) = JsonObject().apply {
    addProperty("jsonrpc", "2.0"); add("id", id); add("result", result)
  }

  private fun error(id: JsonElement, code: Int, message: String) = JsonObject().apply {
    addProperty("jsonrpc", "2.0"); add("id", id)
    add("error", JsonObject().apply { addProperty("code", code); addProperty("message", message) })
  }

  companion object {
    const val OPEN = "open_in_editor"
    const val CONTEXT = "get_editor_context"

    /** Newest first: an unknown version asked for gets the newest. */
    val VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

    private const val PARSE_ERROR = -32700
    private const val INVALID_REQUEST = -32600
    private const val METHOD_NOT_FOUND = -32601

    private const val INSTRUCTIONS =
      "You run in a terminal inside Phantom, the user's IDE. When you point the user at code (where something is defined, " +
        "the line you changed, the cause of a bug), open it with open_in_editor instead of only printing the path. When the " +
        "user says \"this\", \"here\" or refers to what they are looking at, read get_editor_context first."

    private const val OPEN_DESCRIPTION =
      "Opens a file in the user's Phantom editor at a line, selects up to end_line and brings it to front. Use it whenever " +
        "the user asks where something is, or to show the code you are talking about. Opens the file's project if needed."

    private const val CONTEXT_DESCRIPTION =
      "The file the user has open in Phantom's editor right now: its path, the caret line and column, the selected text " +
        "and its lines, and the other open files."
  }
}

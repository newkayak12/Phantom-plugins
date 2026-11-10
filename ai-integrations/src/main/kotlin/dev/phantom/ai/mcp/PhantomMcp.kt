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
 * worktree, the base of relative paths and the project to open in; [index] answers code questions within that worktree.
 * Pure apart from [bridge] and [index].
 */
class PhantomMcp(private val bridge: EditorBridge, private val index: CodeIndex, private val worktreeOf: (String?) -> String?) {
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
        FIND_SYMBOL -> text(index.findSymbol(worktree, required(args, "query"), limit(args)))
        FIND_FILE -> text(index.findFile(worktree, required(args, "query"), limit(args)))
        FIND_USAGES -> text(index.findUsages(worktree, required(args, "path"), line(args), required(args, "name"), limit(args)))
        OUTLINE -> text(index.fileOutline(worktree, required(args, "path")))
        SYMBOL_INFO -> text(index.symbolInfo(worktree, required(args, "path"), line(args), required(args, "name")))
        CALLS -> text(index.calls(worktree, required(args, "path"), line(args), required(args, "name"), limit(args)))
        PROBLEMS -> text(index.fileProblems(worktree, required(args, "path"), args.get("warnings")?.takeIf { it.isJsonPrimitive }?.asString == "true"))
        RENAME -> text(index.rename(worktree, required(args, "path"), line(args), required(args, "name"), required(args, "new_name")))
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
    add(tool(FIND_SYMBOL, FIND_SYMBOL_DESCRIPTION, JsonObject().apply {
      add("query", prop("string", "Name of a class, method, function or field; camel humps work (OSvc)."))
      add("limit", prop("integer", "Most results to return (default $DEFAULT_LIMIT)."))
    }, required = listOf("query"), readOnly = true))
    add(tool(FIND_FILE, FIND_FILE_DESCRIPTION, JsonObject().apply {
      add("query", prop("string", "File name or part of it; camel humps work."))
      add("limit", prop("integer", "Most results to return (default $DEFAULT_LIMIT)."))
    }, required = listOf("query"), readOnly = true))
    add(tool(FIND_USAGES, FIND_USAGES_DESCRIPTION, JsonObject().apply {
      add("path", prop("string", "File holding the symbol, as find_symbol gave it."))
      add("line", prop("integer", "1-based line where the symbol is written."))
      add("name", prop("string", "The symbol's name on that line."))
      add("limit", prop("integer", "Most results to return (default $DEFAULT_LIMIT)."))
    }, required = listOf("path", "line", "name"), readOnly = true))
    add(tool(OUTLINE, OUTLINE_DESCRIPTION, JsonObject().apply {
      add("path", prop("string", "File path, absolute or relative to your working directory."))
    }, required = listOf("path"), readOnly = true))
    add(tool(SYMBOL_INFO, SYMBOL_INFO_DESCRIPTION, symbolAt(), required = listOf("path", "line", "name"), readOnly = true))
    add(tool(CALLS, CALLS_DESCRIPTION, symbolAt().apply {
      add("limit", prop("integer", "Most results per list (default $DEFAULT_LIMIT)."))
    }, required = listOf("path", "line", "name"), readOnly = true))
    add(tool(PROBLEMS, PROBLEMS_DESCRIPTION, JsonObject().apply {
      add("path", prop("string", "File path, absolute or relative to your working directory."))
      add("warnings", prop("boolean", "Warnings too (default: errors only)."))
    }, required = listOf("path"), readOnly = true))
    add(tool(RENAME, RENAME_DESCRIPTION, symbolAt().apply {
      add("new_name", prop("string", "The new name."))
    }, required = listOf("path", "line", "name", "new_name")))
  }

  /** A symbol named where it is written: the arguments find_usages also takes. */
  private fun symbolAt() = JsonObject().apply {
    add("path", prop("string", "File holding the symbol or a use of it."))
    add("line", prop("integer", "1-based line where the name is written."))
    add("name", prop("string", "The symbol's name on that line."))
  }

  private fun required(args: JsonObject, key: String): String = string(args, key) ?: throw McpToolError("$key is required")

  private fun line(args: JsonObject): Int = int(args, "line") ?: throw McpToolError("line is required")

  private fun limit(args: JsonObject): Int = (int(args, "limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

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
    const val FIND_SYMBOL = "find_symbol"
    const val FIND_FILE = "find_file"
    const val FIND_USAGES = "find_usages"
    const val OUTLINE = "file_outline"
    const val SYMBOL_INFO = "get_symbol_info"
    const val CALLS = "analyze_calls"
    const val PROBLEMS = "get_file_problems"
    const val RENAME = "rename_refactoring"

    private const val DEFAULT_LIMIT = 30
    private const val MAX_LIMIT = 200

    /** Newest first: an unknown version asked for gets the newest. */
    val VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

    private const val PARSE_ERROR = -32700
    private const val INVALID_REQUEST = -32600
    private const val METHOD_NOT_FOUND = -32601

    private const val INSTRUCTIONS =
      "You run in a terminal inside Phantom, the user's IDE. When you point the user at code (where something is defined, " +
        "the line you changed, the cause of a bug), open it with open_in_editor instead of only printing the path. When the " +
        "user says \"this\", \"here\" or refers to what they are looking at, read get_editor_context first. To find code, " +
        "ask the IDE's index before searching or reading files: find_symbol, find_file, find_usages, and file_outline before " +
        "reading a whole file; then read only the lines you need. If one says it cannot answer, use your own search. " +
        "After editing a file, get_file_problems shows its errors without a build; rename symbols with rename_refactoring."

    private const val OPEN_DESCRIPTION =
      "Opens a file in the user's Phantom editor at a line, selects up to end_line and brings it to front. Use it whenever " +
        "the user asks where something is, or to show the code you are talking about. Opens the file's project if needed."

    private const val CONTEXT_DESCRIPTION =
      "The file the user has open in Phantom's editor right now: its path, the caret line and column, the selected text " +
        "and its lines, and the other open files."

    private const val FIND_SYMBOL_DESCRIPTION =
      "Finds where a class, method, function or field is declared in this workspace, from the IDE's index. Returns path:line and " +
        "that line of code. Prefer it to grep when you know a name."

    private const val FIND_FILE_DESCRIPTION = "Finds files in this workspace by name, from the IDE's index. Returns relative paths."

    private const val FIND_USAGES_DESCRIPTION =
      "Finds the real references to a symbol in this workspace (no matches in comments or of other symbols with that name). " +
        "Returns path:line and each line of code."

    private const val OUTLINE_DESCRIPTION =
      "The declarations of a file with their lines, nested: its structure without reading it. Use it before reading a large file."

    private const val SYMBOL_INFO_DESCRIPTION = "Where a symbol is declared, its signature and doc comment, without reading its file."

    private const val CALLS_DESCRIPTION =
      "Who calls a function or method, each with the [member] the call is in, and what it calls in this workspace."

    private const val PROBLEMS_DESCRIPTION =
      "A file's errors from the IDE's analysis (warnings with warnings=true), as path:line  ERROR  message. Faster than a build."

    private const val RENAME_DESCRIPTION =
      "Renames a symbol and all its references in this workspace with the IDE's Rename, and saves the changed files. " +
        "Refuses on a name conflict or a change outside this workspace."
  }
}

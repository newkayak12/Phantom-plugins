// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
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

  /**
   * The end of a Run, Debug or test console of the worktree's project: the one whose tab title contains [name], else the
   * selected one; its last [lines] lines, only those matching [grep] when given.
   */
  fun runOutput(worktree: String?, name: String?, lines: Int, grep: String?): String
}

/**
 * Phantom's MCP server for its own agent terminals (JSON-RPC over the hook server's `/mcp`, streamable HTTP answered with
 * plain JSON, no SSE): lets Claude or Codex show the user code in the editor. [worktreeOf] maps the calling tab to its
 * worktree, the base of relative paths and the project to open in; [index] answers code questions within that worktree.
 * find_symbol, find_usages, search_text, file_outline and get_file_problems come from Local Shared Index, through [extra].
 * Pure apart from [bridge] and [index].
 */
class PhantomMcp(
  private val bridge: EditorBridge,
  private val index: CodeIndex,
  /** The tools of [AgentTools] extensions, asked on each list and call: one can come and go with its plugin. */
  private val extra: () -> List<AgentTool> = { emptyList() },
  private val worktreeOf: (String?) -> String?,
) {
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
        FIND_FILE -> text(index.findFile(worktree, required(args, "query"), limit(args)))
        SYMBOL_INFO -> text(index.symbolInfo(worktree, required(args, "path"), line(args), required(args, "name")))
        CALLS -> text(index.calls(worktree, required(args, "path"), line(args), required(args, "name"), limit(args)))
        RENAME -> text(index.rename(worktree, required(args, "path"), line(args), required(args, "name"), required(args, "new_name")))
        READ_SYMBOL -> text(index.readSymbol(worktree, required(args, "path"), int(args, "line"), required(args, "name")))
        REPLACE_SYMBOL -> text(index.replaceSymbol(worktree, required(args, "path"), int(args, "line"), required(args, "name"), code(args)))
        INSERT_CODE -> {
          val after = when (string(args, "position") ?: "after") {
            "after" -> true
            "before" -> false
            else -> throw McpToolError("position is before or after")
          }
          text(index.insertCode(worktree, required(args, "path"), int(args, "line"), required(args, "name"), after, code(args)))
        }
        RUN_OUTPUT -> text(bridge.runOutput(worktree, string(args, "name"), (int(args, "lines") ?: DEFAULT_LINES).coerceIn(1, MAX_LINES), string(args, "grep")))
        else -> extra().firstOrNull { it.name == name }?.let { text(it.call(worktree, args)) } ?: text("Unknown tool: $name", isError = true)
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
    add(tool(FIND_FILE, FIND_FILE_DESCRIPTION, JsonObject().apply {
      add("query", prop("string", "File name or part of it; camel humps work."))
      add("limit", prop("integer", "Most results to return (default $DEFAULT_LIMIT)."))
    }, required = listOf("query"), readOnly = true))
    add(tool(SYMBOL_INFO, SYMBOL_INFO_DESCRIPTION, symbolAt(), required = listOf("path", "line", "name"), readOnly = true))
    add(tool(CALLS, CALLS_DESCRIPTION, symbolAt().apply {
      add("limit", prop("integer", "Most results per list (default $DEFAULT_LIMIT)."))
    }, required = listOf("path", "line", "name"), readOnly = true))
    add(tool(RENAME, RENAME_DESCRIPTION, symbolAt().apply {
      add("new_name", prop("string", "The new name."))
    }, required = listOf("path", "line", "name", "new_name")))
    add(tool(READ_SYMBOL, READ_SYMBOL_DESCRIPTION, declarationIn(), required = listOf("path", "name"), readOnly = true))
    add(tool(REPLACE_SYMBOL, REPLACE_SYMBOL_DESCRIPTION, declarationIn().apply {
      add("code", prop("string", "The whole new declaration, doc comment and annotations included."))
    }, required = listOf("path", "name", "code")))
    add(tool(INSERT_CODE, INSERT_CODE_DESCRIPTION, declarationIn().apply {
      add("position", JsonObject().apply {
        addProperty("type", "string")
        add("enum", JsonArray().apply { add("after"); add("before") })
        addProperty("description", "after (default) or before the declaration.")
      })
      add("code", prop("string", "The code to insert: one or more declarations."))
    }, required = listOf("path", "name", "code")))
    add(tool(RUN_OUTPUT, RUN_OUTPUT_DESCRIPTION, JsonObject().apply {
      add("name", prop("string", "Part of the console tab's title (the run configuration name); the selected console when left out."))
      add("lines", prop("integer", "How many lines from the end (default $DEFAULT_LINES, at most $MAX_LINES)."))
      add("grep", prop("string", "Only lines matching this regular expression (case-insensitive), e.g. ERROR|Exception."))
    }, required = emptyList(), readOnly = true))
    for (tool in extra()) add(tool(tool.name, tool.description, tool.properties, tool.required, tool.readOnly))
  }

  /** A symbol named where it is written: the arguments find_usages also takes. */
  private fun symbolAt() = JsonObject().apply {
    add("path", prop("string", "File holding the symbol or a use of it."))
    add("line", prop("integer", "1-based line where the name is written."))
    add("name", prop("string", "The symbol's name on that line."))
  }

  /** A declaration in a file, by name and optionally its line. */
  private fun declarationIn() = JsonObject().apply {
    add("path", prop("string", "File path, relative to your working directory or absolute."))
    add("name", prop("string", "The declaration's name; Outer.name for one inside Outer (Class.method)."))
    add("line", prop("integer", "1-based line where the name is, only needed when the file declares the name more than once."))
  }

  /** The code argument as sent: blank lines inside it are kept. */
  private fun code(args: JsonObject): String =
    args.get("code")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } ?: throw McpToolError("code is required")

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
    const val FIND_FILE = "find_file"
    const val SYMBOL_INFO = "get_symbol_info"
    const val CALLS = "analyze_calls"
    const val RENAME = "rename_refactoring"
    const val READ_SYMBOL = "read_symbol"
    const val REPLACE_SYMBOL = "replace_symbol"
    const val INSERT_CODE = "insert_code"
    const val RUN_OUTPUT = "get_run_output"

    private const val DEFAULT_LINES = 100
    private const val MAX_LINES = 2000

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
        "ask the IDE's index before searching or reading files: find_symbol, find_file, find_usages, search_text for text, and file_outline before " +
        "reading a whole file; then read only the lines you need. If one says it cannot answer, use your own search. " +
        "To read one method or class, read_symbol instead of the whole file; to rewrite one, replace_symbol or insert_code " +
        "instead of reproducing its old text. After editing a file, get_file_problems shows its errors without a build; " +
        "rename symbols with rename_refactoring. For what a running app or test printed, get_run_output (with grep) " +
        "instead of asking the user to paste it. With Phantom's Database Console, list_databases, list_tables, " +
        "describe_table and query_database (read-only) reach the user's saved data sources: no connection details needed."

    private const val OPEN_DESCRIPTION =
      "Opens a file in the user's Phantom editor at a line, selects up to end_line and brings it to front. Use it whenever " +
        "the user asks where something is, or to show the code you are talking about. Opens the file's project if needed."

    private const val CONTEXT_DESCRIPTION =
      "The file the user has open in Phantom's editor right now: its path, the caret line and column, the selected text " +
        "and its lines, and the other open files."

    private const val FIND_FILE_DESCRIPTION = "Finds files in this workspace by name, from the IDE's index. Returns relative paths."

    private const val SYMBOL_INFO_DESCRIPTION = "Where a symbol is declared, its signature and doc comment, without reading its file."

    private const val CALLS_DESCRIPTION =
      "Who calls a function or method, each with the [member] the call is in, and what it calls in this workspace."

    private const val READ_SYMBOL_DESCRIPTION =
      "The source of one class, method, function or field with its line numbers, doc comment and annotations: only " +
        "those lines instead of the whole file. Find it by name (Outer.name narrows it); line only when the name is declared twice."

    private const val REPLACE_SYMBOL_DESCRIPTION =
      "Replaces one whole declaration (method, function, class, field, with its doc comment and annotations) with new " +
        "code in the IDE, as one undoable edit, and saves the file. No need to send the old text. The code is re-indented " +
        "to the declaration's place: send it at column 0 or with one consistent indentation. Says the new lines."

    private const val INSERT_CODE_DESCRIPTION =
      "Inserts code after (default) or before one declaration, with a blank line between, re-indented to its place, as " +
        "one undoable edit, and saves the file. For a new method next to an existing one. Says the new lines."

    private const val RUN_OUTPUT_DESCRIPTION =
      "What a Run, Debug or test console in Phantom printed: its last lines (grep filters them), and whether the process " +
        "still runs. Lists the consoles when the name matches none."

    private const val RENAME_DESCRIPTION =
      "Renames a symbol and all its references in this workspace with the IDE's Rename, and saves the changed files. " +
        "Refuses on a name conflict or a change outside this workspace."
  }
}

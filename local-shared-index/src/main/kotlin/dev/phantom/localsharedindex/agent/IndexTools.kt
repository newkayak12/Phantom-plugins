// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex.agent

/** One parameter of an [IndexTool]: its JSON schema [type] (string, integer or boolean) and what it is for. */
class IndexToolParam(val name: String, val type: String, val description: String)

/**
 * An agent tool answered from the IDE's index for the calling agent's worktree. [call] gets the arguments as strings
 * (agents send numbers as strings too), runs off the EDT and throws [IndexToolError] for an answer the agent should see
 * as the tool's error. All of them only read.
 */
class IndexTool(
  val name: String,
  val description: String,
  val params: List<IndexToolParam>,
  val required: List<String>,
  val call: (worktree: String?, args: Map<String, String>) -> String,
)

/** The index tools, in the order agents see them; ai-integrations serves them from its MCP server. */
object IndexTools {
  const val FIND_SYMBOL = "find_symbol"
  const val FIND_USAGES = "find_usages"
  const val SEARCH_TEXT = "search_text"
  const val OUTLINE = "file_outline"
  const val PROBLEMS = "get_file_problems"

  fun all(): List<IndexTool> = listOf(
    IndexTool(FIND_SYMBOL, FIND_SYMBOL_DESCRIPTION, listOf(
      IndexToolParam("query", "string", "Name of a class, method, function or field; camel humps work (OSvc)."),
      limitParam(),
    ), listOf("query")) { worktree, args ->
      val query = required(args, "query")
      IndexWorkspaces.read(worktree) { it.queries.symbols(query, limit(args)) }
    },
    IndexTool(FIND_USAGES, FIND_USAGES_DESCRIPTION, listOf(
      IndexToolParam("path", "string", "File holding the symbol, as find_symbol gave it."),
      IndexToolParam("line", "integer", "1-based line where the symbol is written."),
      IndexToolParam("name", "string", "The symbol's name on that line."),
      limitParam(),
    ), listOf("path", "line", "name")) { worktree, args ->
      val path = required(args, "path")
      val line = int(args, "line") ?: throw IndexToolError("line is required")
      val name = required(args, "name")
      IndexWorkspaces.read(worktree) { it.queries.usages(it.file(path), line, name, limit(args)) }
    },
    IndexTool(SEARCH_TEXT, SEARCH_TEXT_DESCRIPTION, listOf(
      IndexToolParam("text", "string", "The text to find, as written in the files; a regular expression with regex=true."),
      IndexToolParam("regex", "boolean", "Treat text as a regular expression (default false)."),
      IndexToolParam("case_sensitive", "boolean", "Match case (default false)."),
      IndexToolParam("context", "integer", "Lines to show before and after each match (default $DEFAULT_CONTEXT, at most $MAX_CONTEXT)."),
      limitParam(),
    ), listOf("text")) { worktree, args ->
      val text = args["text"]?.takeIf { it.isNotEmpty() } ?: throw IndexToolError("text is required")
      val context = (int(args, "context") ?: DEFAULT_CONTEXT).coerceIn(0, MAX_CONTEXT)
      IndexWorkspaces.searchText(worktree, text, bool(args, "regex"), bool(args, "case_sensitive"), context, limit(args))
    },
    IndexTool(OUTLINE, OUTLINE_DESCRIPTION, listOf(pathParam()), listOf("path")) { worktree, args ->
      val path = required(args, "path")
      IndexWorkspaces.read(worktree) { it.queries.outline(it.file(path)) }
    },
    IndexTool(PROBLEMS, PROBLEMS_DESCRIPTION, listOf(
      pathParam(),
      IndexToolParam("warnings", "boolean", "Warnings too (default: errors only)."),
    ), listOf("path")) { worktree, args -> IndexWorkspaces.problems(worktree, required(args, "path"), bool(args, "warnings")) },
  )

  private fun limitParam() = IndexToolParam("limit", "integer", "Most results to return (default $DEFAULT_LIMIT).")

  private fun pathParam() = IndexToolParam("path", "string", "File path, absolute or relative to your working directory.")

  private fun required(args: Map<String, String>, key: String): String = args[key]?.takeIf { it.isNotBlank() } ?: throw IndexToolError("$key is required")

  private fun int(args: Map<String, String>, key: String): Int? = args[key]?.trim()?.toDoubleOrNull()?.toInt()

  private fun bool(args: Map<String, String>, key: String): Boolean = args[key]?.trim() == "true"

  private fun limit(args: Map<String, String>): Int = (int(args, "limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

  private const val DEFAULT_LIMIT = 30
  private const val MAX_LIMIT = 200
  private const val DEFAULT_CONTEXT = 2
  private const val MAX_CONTEXT = 5

  private const val FIND_SYMBOL_DESCRIPTION =
    "Finds where a class, method, function or field is declared in this workspace, from the IDE's index. Returns path:line and " +
      "that line of code. Prefer it to grep when you know a name."

  private const val FIND_USAGES_DESCRIPTION =
    "Finds the real references to a symbol in this workspace (no matches in comments or of other symbols with that name). " +
      "Returns path:line and each line of code."

  private const val SEARCH_TEXT_DESCRIPTION =
    "Finds text in this workspace's project files with the IDE's Find in Files and its indexes, faster than grep on a large " +
      "project. Returns path:line and the lines around each match, the matching ones marked with >."

  private const val OUTLINE_DESCRIPTION =
    "The declarations of a file with their lines, nested: its structure without reading it. Use it before reading a large file."

  private const val PROBLEMS_DESCRIPTION =
    "A file's errors from the IDE's analysis (warnings with warnings=true), as path:line  ERROR  message. Faster than a build."
}

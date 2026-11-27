// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.mcp

import com.google.gson.JsonObject
import dev.phantom.localsharedindex.agent.IndexToolError
import dev.phantom.localsharedindex.agent.IndexTools

/**
 * The IDE index tools of Local Shared Index (find_symbol, find_usages, search_text, file_outline, get_file_problems) in
 * Phantom's MCP server. Local Shared Index knows nothing of this plugin; this adapter gives its tools a JSON schema and
 * turns its errors into tool errors.
 */
class IndexAgentTools : AgentTools {
  override fun tools(): List<AgentTool> = IndexTools.all().map { tool ->
    val properties = JsonObject().apply {
      for (param in tool.params) add(param.name, JsonObject().apply { addProperty("type", param.type); addProperty("description", param.description) })
    }
    AgentTool(tool.name, tool.description, properties, tool.required, readOnly = true) { worktree, args ->
      val strings = args.entrySet().filter { it.value.isJsonPrimitive }.associate { it.key to it.value.asString }
      try {
        tool.call(worktree, strings)
      } catch (e: IndexToolError) {
        throw McpToolError(e.message ?: "Failed")
      }
    }
  }
}

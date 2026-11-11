// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.mcp

import com.google.gson.JsonObject
import com.intellij.openapi.extensions.ExtensionPointName

/**
 * More MCP tools for the agents, from an optional part of this plugin (the database tools, with Phantom Database
 * Console). [AgentTool.call] runs off the EDT; a [McpToolError] it throws goes back to the agent as the tool's error.
 */
interface AgentTools {
  fun tools(): List<AgentTool>

  companion object {
    val EP: ExtensionPointName<AgentTools> = ExtensionPointName.create("dev.phantom.ai.agentTools")

    fun all(): List<AgentTool> = EP.extensionList.flatMap { it.tools() }
  }
}

/** One tool: its JSON schema [properties] and [required] names, and what it does for the calling agent's worktree. */
class AgentTool(
  val name: String,
  val description: String,
  val properties: JsonObject,
  val required: List<String>,
  val readOnly: Boolean,
  val call: (worktree: String?, arguments: JsonObject) -> String,
)

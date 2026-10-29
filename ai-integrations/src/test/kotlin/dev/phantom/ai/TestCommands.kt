// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

/** An agent command as it would be without Phantom's MCP server (its per-tab config path and port vary per run). */
fun withoutMcp(command: String): String =
  command.replace(Regex(" '--mcp-config' '[^']*'"), "").replace(Regex(" '-c' 'mcp_servers\\.phantom\\.[^']*'"), "")

// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.launch

import dev.phantom.ai.accounts.AgentAccount
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.SystemDirs

/**
 * The exact shell commands and env of agent terminals. Pure. There is deliberately no parameter that changes the
 * permission flag: every command carries it.
 */
object LaunchCommand {
  const val CLAUDE_CONFIG_DIR = "CLAUDE_CONFIG_DIR"
  const val CODEX_HOME = "CODEX_HOME"

  /** POSIX single-quote escaping: ' becomes '\'' . */
  fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  /** The CLI word: bare `claude`/`codex`, or the quoted stub path with -Dphantom.ai.agentBinDir. */
  private fun cli(kind: AgentKind): String = SystemDirs.cli(kind).let { if (it == "claude" || it == "codex") it else quote(it) }

  /**
   * A new agent; with [prompt], the agent starts on it as its first message. Claude takes it before its options, as
   * --mcp-config would take a word after it as one more config file.
   */
  fun launch(kind: AgentKind, mcp: McpLink? = null, prompt: String? = null): String = when (kind) {
    AgentKind.CLAUDE -> "${cli(kind)}${prompt?.let { " " + quote(it) } ?: ""} '--dangerously-skip-permissions'${mcpArgs(kind, mcp)}"
    AgentKind.CODEX -> "${cli(kind)}${mcpArgs(kind, mcp)} '--dangerously-bypass-approvals-and-sandbox'${prompt?.let { " " + quote(it) } ?: ""}"
  }

  fun resume(kind: AgentKind, id: String, mcp: McpLink? = null): String = when (kind) {
    AgentKind.CLAUDE -> "${cli(kind)} '--dangerously-skip-permissions'${mcpArgs(kind, mcp)} '--resume' ${quote(id)}"
    AgentKind.CODEX -> "${cli(kind)}${mcpArgs(kind, mcp)} resume '--dangerously-bypass-approvals-and-sandbox' ${quote(id)}"
  }

  /**
   * Phantom's MCP server for the agent ([dev.phantom.ai.mcp.PhantomMcp]): Claude reads it from a config file (the token stays
   * off the command line), Codex from `-c` overrides with the bearer token read from [tokenEnv]. Empty without [mcp].
   */
  private fun mcpArgs(kind: AgentKind, mcp: McpLink?): String {
    mcp ?: return ""
    return when (kind) {
      AgentKind.CLAUDE -> " '--mcp-config' ${quote(mcp.claudeConfigFile)}"
      AgentKind.CODEX ->
        " '-c' ${quote("mcp_servers.${McpLink.NAME}.url=\"${mcp.url}\"")}" +
          " '-c' ${quote("mcp_servers.${McpLink.NAME}.bearer_token_env_var=\"${mcp.tokenEnv}\"")}"
    }
  }

  /** The CLI's own login; the CLI keeps the tokens in the account dir or its keychain, Phantom stores none. */
  fun login(kind: AgentKind): String = when (kind) {
    AgentKind.CLAUDE -> "${cli(kind)} 'auth' 'login' '--claudeai'"
    AgentKind.CODEX -> "${cli(kind)} 'login'"
  }

  /** Account variable only for an added account (System default = null = none), plus the hook env. */
  fun env(kind: AgentKind, account: AgentAccount?, hookEnv: Map<String, String>): Map<String, String> =
    accountEnv(kind, account) + hookEnv

  fun accountEnv(kind: AgentKind, account: AgentAccount?): Map<String, String> {
    // System default = no variable, unless -Dphantom.ai.*Dir redirects it (then the redirected dir)
    val dir = account?.dir ?: SystemDirs.redirect(kind) ?: return emptyMap()
    return when (kind) {
      AgentKind.CLAUDE -> mapOf(CLAUDE_CONFIG_DIR to dir)
      AgentKind.CODEX -> mapOf(CODEX_HOME to dir)
    }
  }
}

/** Where an agent reaches Phantom's MCP server: its [url], the env variable holding the token, Claude's config file. */
data class McpLink(val url: String, val tokenEnv: String, val claudeConfigFile: String) {
  companion object {
    /** The server name the agents see: tools show up as `mcp__phantom__open_in_editor` and so on. */
    const val NAME = "phantom"
  }
}

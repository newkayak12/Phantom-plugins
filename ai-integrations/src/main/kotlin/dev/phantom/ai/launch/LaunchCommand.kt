// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
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

  fun launch(kind: AgentKind): String = when (kind) {
    AgentKind.CLAUDE -> "${cli(kind)} '--dangerously-skip-permissions'"
    AgentKind.CODEX -> "${cli(kind)} '--dangerously-bypass-approvals-and-sandbox'"
  }

  fun resume(kind: AgentKind, id: String): String = when (kind) {
    AgentKind.CLAUDE -> "${cli(kind)} '--dangerously-skip-permissions' '--resume' ${quote(id)}"
    AgentKind.CODEX -> "${cli(kind)} resume '--dangerously-bypass-approvals-and-sandbox' ${quote(id)}"
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

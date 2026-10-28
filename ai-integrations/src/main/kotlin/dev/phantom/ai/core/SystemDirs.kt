// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.core

import java.nio.file.Path

/**
 * The one source of truth for the "System default" config dirs. With -Dphantom.ai.claudeDir / -Dphantom.ai.codexDir
 * set (a sandbox or probe) the System default is that dir everywhere; with none set it is the CLI's own default.
 */
object SystemDirs {
  const val CLAUDE_DIR_PROP = "phantom.ai.claudeDir"
  const val CODEX_DIR_PROP = "phantom.ai.codexDir"

  const val AGENT_BIN_DIR_PROP = "phantom.ai.agentBinDir"
  const val KEYCHAIN_PROP = "phantom.ai.keychain"

  /** The CLI to run for [kind]: `claude`/`codex` from PATH, or that name inside -Dphantom.ai.agentBinDir (probe stubs). */
  fun cli(kind: AgentKind): String {
    val name = if (kind == AgentKind.CLAUDE) "claude" else "codex"
    val bin = System.getProperty(AGENT_BIN_DIR_PROP)?.takeIf { it.isNotBlank() } ?: return name
    return Path.of(bin, name).toString()
  }

  /** False only with -Dphantom.ai.keychain=false (the probe sandbox): the macOS keychain is then never read. */
  fun keychainAllowed(): Boolean = System.getProperty(KEYCHAIN_PROP)?.trim()?.lowercase() != "false"

  /** The redirected dir, or null when the property is not set (the System default then sets no env var). */
  fun redirect(kind: AgentKind): String? =
    System.getProperty(if (kind == AgentKind.CLAUDE) CLAUDE_DIR_PROP else CODEX_DIR_PROP)?.takeIf { it.isNotBlank() }

  /** The System default dir of [kind]. */
  fun dir(kind: AgentKind): Path = when (kind) {
    AgentKind.CLAUDE -> redirect(kind)?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".claude")
    AgentKind.CODEX -> redirect(kind)?.let { Path.of(it) }
      ?: System.getenv("CODEX_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
      ?: Path.of(System.getProperty("user.home"), ".codex")
  }
}

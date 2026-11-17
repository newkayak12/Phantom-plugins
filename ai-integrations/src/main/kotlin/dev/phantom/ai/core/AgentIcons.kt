// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.core

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** The official Claude Code and Codex marks (16px, light and dark), as the bundled terminal plugin shows them. */
object AgentIcons {
  @JvmField val CLAUDE: Icon = IconLoader.getIcon("/icons/agents/claude-code.svg", AgentIcons::class.java)
  @JvmField val CODEX: Icon = IconLoader.getIcon("/icons/agents/codex.svg", AgentIcons::class.java)

  fun of(kind: AgentKind): Icon = when (kind) {
    AgentKind.CLAUDE -> CLAUDE
    AgentKind.CODEX -> CODEX
  }
}

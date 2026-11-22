// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import dev.phantom.ai.core.AiState

/**
 * Where agent terminals show: the IDE's own tab (`ide`), iTerm (`iterm`), the OS terminal (`os`), or one another plugin
 * adds through the `dev.phantom.ai.terminalBackend` extension point (an external terminal app, say). The agent runs in
 * Phantom's tmux session where tmux is installed, so a terminal outside the IDE only attaches to it; the session list,
 * its state (from the agents' hooks) and closing a session stay in the IDE.
 */
interface TerminalBackend {
  /** Stable id, kept in the settings. */
  val id: String

  /** Name in Settings | Tools | Phantom AI Integration | Terminal. */
  val displayName: String

  /** Whether it can run here (iTerm installed, a supported OS); one that can't is not offered. */
  fun isAvailable(): Boolean = true

  fun starter(project: Project): TerminalStarter

  companion object {
    val EP_NAME: ExtensionPointName<TerminalBackend> = ExtensionPointName.create("dev.phantom.ai.terminalBackend")
  }
}

object TerminalBackends {
  const val IDE = "ide"
  const val OS = "os"

  fun available(): List<TerminalBackend> = TerminalBackend.EP_NAME.extensionList.filter { it.isAvailable() }

  /**
   * The backend to use: the one chosen in the settings while it is available, else automatic: the IDE's tab where tmux is
   * installed (the agent outlives the window there), the OS terminal where it is not, the IDE's tab where neither runs.
   */
  fun current(
    chosen: String? = AiState.getInstance().state.terminalBackend,
    backends: List<TerminalBackend> = available(),
    tmuxInstalled: Boolean = Tmux.binary() != null,
  ): TerminalBackend? {
    chosen?.ifBlank { null }?.let { id -> backends.firstOrNull { it.id == id }?.let { return it } }
    val auto = if (tmuxInstalled) IDE else OS
    return backends.firstOrNull { it.id == auto } ?: backends.firstOrNull { it.id == IDE }
  }
}

/** The IDE's own terminal tab (the bundled Terminal plugin). */
class IdeTerminalBackend : TerminalBackend {
  override val id = TerminalBackends.IDE
  override val displayName get() = dev.phantom.ai.core.PhantomAiBundle.message("terminal.backend.ide")
  override fun starter(project: Project): TerminalStarter = LocalTerminalStarter(project)
}

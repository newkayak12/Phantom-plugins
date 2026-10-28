// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.util.UUID
import javax.swing.JComponent

/** A started terminal: the component to show in a tab, what should get the focus, and its screen/keys access for the remote control. */
class StartedTerminal(val component: JComponent, val preferredFocus: JComponent? = null, val io: TerminalIo? = null)

/**
 * The seam to the bundled terminal plugin, so headless tests can fake it. [start] creates a shell in [cwd] with
 * [env] added to the environment, runs [command] in it, and ties the terminal to [parent]. It does not add it to any
 * tool window: that is the caller's job (an [AgentTabsHost] tab, never the Terminal tool window).
 */
fun interface TerminalStarter {
  fun start(parent: Disposable, cwd: String, env: Map<String, String>, command: String): StartedTerminal
}

/** Real terminal: LocalTerminalDirectRunner of the bundled terminal plugin. Call on the EDT. */
class LocalTerminalStarter(private val project: Project) : TerminalStarter {
  override fun start(parent: Disposable, cwd: String, env: Map<String, String>, command: String): StartedTerminal {
    val options = ShellStartupOptions.Builder().workingDirectory(cwd).envVariables(env).build()
    val widget = LocalTerminalDirectRunner.createTerminalRunner(project).startShellTerminalWidget(parent, options, true)
    // in tmux when installed (mouse mode, panes); the session ends with the tab
    // in tmux (mouse mode, panes), installed with Homebrew first where it is missing; the session ends with the tab
    val name = "phantom-" + UUID.randomUUID().toString().take(8)
    val tmux = Tmux.binary()
    val brew = if (tmux == null) Tmux.brew() else null
    val line = when {
      tmux != null -> Tmux.wrap(tmux, name, cwd, env, command)
      brew != null -> Tmux.installThenWrap(brew, name, cwd, env, command)
      else -> command
    }
    val installed = tmux ?: brew?.let { java.io.File(it).resolveSibling("tmux").path }
    if (installed != null) {
      Disposer.register(parent) { ApplicationManager.getApplication().executeOnPooledThread { Tmux.kill(installed, name) } }
    }
    widget.sendCommandToExecute(line)
    // files dropped on the terminal are typed as paths (images as attachments), as in the Terminal tool window
    val jediTerm = ShellTerminalWidget.asShellJediTermWidget(widget)
    if (jediTerm != null) TerminalDrop.install(jediTerm.terminalPanel, parent) { jediTerm.terminalStarter?.sendString(it, true) }
    return StartedTerminal(widget.component, widget.preferredFocusableComponent, jediTerm?.let { JediTermIo(it) })
  }
}

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
 * [env] added to the environment, running [command], and ties the terminal to [parent]. It does not add it to any
 * tool window: that is the caller's job (an [AgentTabsHost] tab, never the Terminal tool window).
 */
fun interface TerminalStarter {
  fun start(parent: Disposable, cwd: String, env: Map<String, String>, command: String): StartedTerminal
}

/** Real terminal: LocalTerminalDirectRunner of the bundled terminal plugin. Call on the EDT. */
class LocalTerminalStarter(private val project: Project) : TerminalStarter {
  override fun start(parent: Disposable, cwd: String, env: Map<String, String>, command: String): StartedTerminal {
    // in tmux (mouse mode, panes) where installed; the session ends with the tab
    val name = "phantom-" + UUID.randomUUID().toString().take(8)
    val tmux = Tmux.binary()
    if (tmux == null) Tmux.brew()?.let(Tmux::installInBackground)
    val line = if (tmux != null) Tmux.wrap(tmux, name, cwd, env, command) else Tmux.plain(command)
    if (tmux != null) {
      Disposer.register(parent) { ApplicationManager.getApplication().executeOnPooledThread { Tmux.kill(tmux, name) } }
    }
    // the agent is the shell's argument, not typed at its prompt: no command line (or hook token) is echoed on screen
    val options = ShellStartupOptions.Builder().workingDirectory(cwd).envVariables(env).shellCommand(Tmux.shellCommand(line)).build()
    val widget = LocalTerminalDirectRunner.createTerminalRunner(project).startShellTerminalWidget(parent, options, true)
    // files dropped on the terminal are typed as paths (images as attachments), as in the Terminal tool window
    val jediTerm = ShellTerminalWidget.asShellJediTermWidget(widget)
    if (jediTerm != null) TerminalDrop.install(jediTerm.terminalPanel, parent) { jediTerm.terminalStarter?.sendString(it, true) }
    return StartedTerminal(widget.component, widget.preferredFocusableComponent, jediTerm?.let { JediTermIo(it) })
  }
}

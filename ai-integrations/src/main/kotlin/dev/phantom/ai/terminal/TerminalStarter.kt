// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import javax.swing.JComponent

/** A started terminal: the component to show in a content tab, and what should get the focus. */
class StartedTerminal(val component: JComponent, val preferredFocus: JComponent? = null)

/**
 * The seam to the bundled terminal plugin, so headless tests can fake it. [start] creates a shell in [cwd] with
 * [env] added to the environment, runs [command] in it, and ties the terminal to [parent]. It does not add it to any
 * tool window: that is the caller's job (never the Terminal tool window).
 */
fun interface TerminalStarter {
  fun start(parent: Disposable, cwd: String, env: Map<String, String>, command: String): StartedTerminal
}

/** Real terminal: LocalTerminalDirectRunner of the bundled terminal plugin. Call on the EDT. */
class LocalTerminalStarter(private val project: Project) : TerminalStarter {
  override fun start(parent: Disposable, cwd: String, env: Map<String, String>, command: String): StartedTerminal {
    val options = ShellStartupOptions.Builder().workingDirectory(cwd).envVariables(env).build()
    val widget = LocalTerminalDirectRunner.createTerminalRunner(project).startShellTerminalWidget(parent, options, true)
    widget.sendCommandToExecute(command)
    return StartedTerminal(widget.component, widget.preferredFocusableComponent)
  }
}

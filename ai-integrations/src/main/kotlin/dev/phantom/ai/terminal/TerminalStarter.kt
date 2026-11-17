// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.terminal.JBTerminalPanel
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.event.KeyEvent
import java.util.UUID
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * A started terminal: the component to show in a tab, what should get the focus, and its screen/keys access for the remote
 * control. [tmuxSession] is the tmux session the command runs in (null: the plain shell); the terminal is only its client.
 */
class StartedTerminal(
  val component: JComponent,
  val preferredFocus: JComponent? = null,
  val io: TerminalIo? = null,
  val tmuxSession: String? = null,
)

/**
 * The seam to the bundled terminal plugin, so headless tests can fake it. [start] creates a shell in [cwd] with
 * [env] added to the environment, running [command], and ties the terminal to [parent]. It does not add it to any
 * tool window: that is the caller's job (an [AgentTabsHost] tab, never the Terminal tool window). Ending the terminal
 * leaves its tmux session running: whoever owns the tab ends that.
 */
fun interface TerminalStarter {
  fun start(parent: Disposable, cwd: String, env: Map<String, String>, command: String): StartedTerminal

  /** A terminal in [cwd] showing the running tmux session [session], tied to [parent]. Fakes get a [start] of the attach command. */
  fun attach(parent: Disposable, cwd: String, session: String): StartedTerminal =
    start(parent, cwd, emptyMap(), Tmux.attach("tmux", session)).let { StartedTerminal(it.component, it.preferredFocus, it.io, session) }
}

private const val ESC = "\u001b"

/**
 * Esc in an agent terminal is the agent's (Claude stops its turn on it), never the IDE's: outside the Terminal tool window the
 * IDE takes it first (to move the focus back to the editor). An action on the terminal's own component runs before the keymap.
 */
internal fun escapeToAgent(component: JComponent, parent: Disposable, send: () -> Unit) {
  object : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = send()
  }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)), component, parent)
}

/**
 * With "Override IDE shortcuts" on (the default) the panel gets keys before actions and the keymap, and outside the Terminal
 * tool window it moves Esc to the editor by itself. Its pre-key handlers run before that; a consumed key reaches no action.
 */
internal fun escapeBeforeThePanel(panel: JBTerminalPanel, send: () -> Unit) {
  panel.addPreKeyEventHandler { e ->
    if (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ESCAPE && e.modifiersEx == 0) {
      send()
      e.consume()
    }
  }
}

/** Real terminal: LocalTerminalDirectRunner of the bundled terminal plugin. Call on the EDT. */
class LocalTerminalStarter(private val project: Project) : TerminalStarter {
  override fun start(parent: Disposable, cwd: String, env: Map<String, String>, command: String): StartedTerminal {
    // in tmux (mouse mode, panes) where installed
    val name = "phantom-" + UUID.randomUUID().toString().take(8)
    val tmux = Tmux.binary()
    if (tmux == null) Tmux.brew()?.let(Tmux::installInBackground)
    val line = if (tmux != null) Tmux.wrap(tmux, name, cwd, env, command) else Tmux.plain(command)
    return run(parent, cwd, env, line, name.takeIf { tmux != null })
  }

  override fun attach(parent: Disposable, cwd: String, session: String): StartedTerminal {
    val tmux = Tmux.binary() ?: error("tmux is not installed")
    return run(parent, cwd, emptyMap(), Tmux.attach(tmux, session), session)
  }

  private fun run(parent: Disposable, cwd: String, env: Map<String, String>, line: String, tmuxSession: String?): StartedTerminal {
    // the agent is the shell's argument, not typed at its prompt: no command line (or hook token) is echoed on screen
    val options = ShellStartupOptions.Builder().workingDirectory(cwd).envVariables(env).shellCommand(Tmux.shellCommand(line)).build()
    val widget = LocalTerminalDirectRunner.createTerminalRunner(project).startShellTerminalWidget(parent, options, true)
    // files dropped on the terminal are typed as paths (images as attachments), as in the Terminal tool window
    val jediTerm = ShellTerminalWidget.asShellJediTermWidget(widget)
    if (jediTerm != null) {
      TerminalDrop.install(jediTerm.terminalPanel, parent) { jediTerm.terminalStarter?.sendString(it, true) }
      val sendEscape = { jediTerm.terminalStarter?.sendString(ESC, true); Unit }
      escapeBeforeThePanel(jediTerm.terminalPanel, sendEscape)
      escapeToAgent(jediTerm.terminalPanel, parent, sendEscape)
    }
    return StartedTerminal(widget.component, widget.preferredFocusableComponent, jediTerm?.let { JediTermIo(it) }, tmuxSession)
  }
}

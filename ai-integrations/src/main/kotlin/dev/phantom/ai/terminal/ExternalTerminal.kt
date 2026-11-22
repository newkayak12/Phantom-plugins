// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.launch.LaunchCommand.quote
import java.awt.FlowLayout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/** Opens a terminal window outside the IDE that runs [script] (an executable shell script) and returns. */
fun interface TerminalOpener {
  fun open(script: Path)
}

/**
 * Agent terminals in a terminal app outside the IDE. With tmux the agent runs in a detached session of Phantom's server
 * and the window only attaches to it, so closing the window leaves the agent running and the IDE tab ([placeholder])
 * can open it again; without tmux the window runs the agent itself. The window gets no screen access for the remote
 * control.
 */
class ExternalTerminalStarter(
  private val appName: String,
  private val opener: TerminalOpener,
  private val tmuxBinary: () -> String? = Tmux::binary,
  private val runDetached: (String) -> Unit = ::runInBackground,
) : TerminalStarter {
  override fun start(parent: Disposable, cwd: String, env: Map<String, String>, command: String): StartedTerminal {
    val tmux = tmuxBinary()
    if (tmux == null) {
      opener.open(script(cwd, env, Tmux.plain(command)))
      return StartedTerminal(placeholder(null))
    }
    val session = "phantom-" + UUID.randomUUID().toString().take(8)
    runDetached(Tmux.wrap(tmux, session, cwd, env, command, detached = true))
    return attach(parent, cwd, session)
  }

  override fun attach(parent: Disposable, cwd: String, session: String): StartedTerminal {
    val tmux = tmuxBinary() ?: error("tmux is not installed")
    val open = { opener.open(script(cwd, emptyMap(), Tmux.attach(tmux, session))) }
    open()
    return StartedTerminal(placeholder(open), tmuxSession = session)
  }

  /** The IDE tab of an agent running outside: where it runs and, with tmux, a button that opens its window again. */
  private fun placeholder(reopen: (() -> Unit)?): JPanel {
    val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(12) }
    panel.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(JBLabel(PhantomAiBundle.message("terminal.external.running", appName))) })
    if (reopen != null) {
      panel.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(JBLabel(PhantomAiBundle.message("terminal.external.close"))) })
      panel.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(JButton(PhantomAiBundle.message("terminal.external.open", appName)).apply { addActionListener { reopen() } })
      })
    }
    return panel
  }

  companion object {
    /**
     * A one-shot script that runs [line] in [cwd] in the user's login shell with [env] added, and deletes itself first: the
     * environment can hold the hook token, so it is readable by the user only and does not stay on disk.
     */
    fun script(cwd: String, env: Map<String, String>, line: String, dir: Path = Path.of(PathManager.getTempPath(), "phantom-ai")): Path {
      Files.createDirectories(dir)
      val file = dir.resolve("agent-" + UUID.randomUUID().toString().take(8) + ".sh")
      val text = buildString {
        append("#!/bin/sh\n")
        append("rm -f -- \"$0\"\n")
        append("cd ").append(quote(cwd)).append(" || exit 1\n")
        env.toSortedMap().forEach { (k, v) -> append("export ").append(k).append('=').append(quote(v)).append('\n') }
        append("exec ").append(Tmux.shellCommand(line).joinToString(" ") { quote(it) }).append('\n')
      }
      if (SystemInfo.isWindows) {
        Files.writeString(file, text)
      }
      else {
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        Files.writeString(file, text)
      }
      return file
    }

    /** Runs the shell line [line] (tmux new-session -d) in the user's login shell and waits for it, bounded. */
    fun runInBackground(line: String) {
      val p = ProcessBuilder(Tmux.shellCommand(line)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
      p.outputStream.close()
      if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly()
    }

    internal fun osascript(vararg lines: String) {
      val args = buildList { add("osascript"); lines.forEach { add("-e"); add(it) } }
      ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().outputStream.close()
    }

    /** [s] as an AppleScript string literal. */
    internal fun appleString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  }
}

/** iTerm2 on macOS, where it is installed: a new window running the script. */
class ITermBackend : TerminalBackend {
  override val id = "iterm"
  override val displayName = "iTerm"
  override fun isAvailable() = SystemInfo.isMac && listOf("/Applications/iTerm.app", System.getProperty("user.home") + "/Applications/iTerm.app").any { File(it).isDirectory }
  override fun starter(project: Project): TerminalStarter = ExternalTerminalStarter(displayName, TerminalOpener { script ->
    ExternalTerminalStarter.osascript(
      "tell application \"iTerm\"",
      "activate",
      "create window with default profile command " + ExternalTerminalStarter.appleString(script.toString()),
      "end tell",
    )
  })
}

/** The OS terminal: Terminal on macOS, x-terminal-emulator (else gnome-terminal) on Linux. */
class OsTerminalBackend : TerminalBackend {
  override val id = TerminalBackends.OS
  override val displayName get() = PhantomAiBundle.message("terminal.backend.os")
  override fun isAvailable() = SystemInfo.isMac || (SystemInfo.isLinux && linuxTerminal() != null)
  override fun starter(project: Project): TerminalStarter = ExternalTerminalStarter(displayName, TerminalOpener { script ->
    if (SystemInfo.isMac) {
      // Launch Services runs the script in a new Terminal window: no Apple event, so no Automation permission to ask for
      ProcessBuilder("open", "-a", "Terminal", script.toString()).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().outputStream.close()
    }
    else {
      val terminal = linuxTerminal() ?: error("no terminal found")
      val args = if (terminal.endsWith("gnome-terminal")) listOf(terminal, "--", script.toString()) else listOf(terminal, "-e", script.toString())
      ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().outputStream.close()
    }
  })

  private fun linuxTerminal(): String? =
    listOf("/usr/bin/x-terminal-emulator", "/usr/bin/gnome-terminal").firstOrNull { File(it).canExecute() }
}

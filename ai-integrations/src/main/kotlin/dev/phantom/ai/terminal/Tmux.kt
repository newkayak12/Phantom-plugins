// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import dev.phantom.ai.launch.LaunchCommand.quote
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Agents run inside tmux when it is installed: tmux's mouse mode (click, scroll, select) and its panes, which Claude's
 * agent teams split into. Phantom keeps its own server (`-L phantom`), apart from the user's sessions. Without tmux the
 * command runs in the plain shell as before.
 */
object Tmux {
  const val SOCKET = "phantom"

  /**
   * The tmux binary: -Dphantom.ai.tmux (empty = never), else the first install found. PATH is not searched: it may hold
   * a wrapper that is not tmux (Orca puts an agent-teams shim named tmux first on it).
   */
  fun binary(): String? {
    System.getProperty("phantom.ai.tmux")?.let { return it.ifBlank { null } }
    return listOf("/opt/homebrew/bin/tmux", "/usr/local/bin/tmux", "/usr/bin/tmux").firstOrNull { File(it).canExecute() }
  }

  /** Homebrew, to install tmux where it is missing; null with -Dphantom.ai.tmux set (tests, probes) or without Homebrew. */
  fun brew(): String? {
    if (System.getProperty("phantom.ai.tmux") != null) return null
    return listOf("/opt/homebrew/bin/brew", "/usr/local/bin/brew").firstOrNull { File(it).canExecute() }
  }

  /**
   * Without tmux: installs it with [brew] in the terminal itself, where the user sees it, then runs [command] in tmux
   * ([wrap]); if the install fails, [command] runs in the plain shell.
   */
  fun installThenWrap(brew: String, name: String, cwd: String, env: Map<String, String>, command: String): String {
    val tmux = File(brew).resolveSibling("tmux").path
    return "${quote(brew)} install tmux && ${wrap(tmux, name, cwd, env, command)} || $command"
  }

  /**
   * The shell command that runs [command] in a new tmux session [name] in [cwd], with [env] set in it (a running tmux
   * server does not take the client's environment), mouse mode on and no status line. When the agent exits the session
   * keeps a login shell, as the plain terminal does.
   */
  fun wrap(tmux: String, name: String, cwd: String, env: Map<String, String>, command: String): String {
    val inner = "$command; exec \"\${SHELL:-/bin/zsh}\" -l"
    val args = buildList {
      add(tmux); add("-L"); add(SOCKET); add("new-session"); add("-s"); add(name); add("-c"); add(cwd)
      env.toSortedMap().forEach { (k, v) -> add("-e"); add("$k=$v") }
      add(inner)
      add(";"); add("set-option"); add("-t"); add(name); add("mouse"); add("on")
      add(";"); add("set-option"); add("-t"); add(name); add("status"); add("off")
    }
    return "exec " + args.joinToString(" ") { quote(it) }
  }

  /** Ends session [name]; the agent goes with its tab. Best effort, bounded. */
  fun kill(tmux: String, name: String) {
    runCatching {
      ProcessBuilder(tmux, "-L", SOCKET, "kill-session", "-t", name).redirectErrorStream(true).start().apply {
        outputStream.close()
        waitFor(3, TimeUnit.SECONDS)
      }
    }
  }
}

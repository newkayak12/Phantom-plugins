// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import dev.phantom.ai.launch.LaunchCommand.quote
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Agents run inside tmux when it is installed: tmux's mouse mode (click, scroll, select) and its panes, which Claude's
 * agent teams split into. Phantom keeps its own server (`-L phantom`), apart from the user's sessions. Without tmux the
 * command runs in the plain shell, and tmux is installed in the background for the next terminal.
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

  private val installing = java.util.concurrent.atomic.AtomicBoolean()

  /**
   * Without tmux: installs it with [brew] once per IDE run, off screen, so the next agent terminal gets it; this one runs
   * in the plain shell. Best effort.
   */
  fun installInBackground(brew: String) {
    if (!installing.compareAndSet(false, true)) return
    Thread({
      runCatching {
        ProcessBuilder(brew, "install", "tmux").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .apply { environment()["HOMEBREW_NO_AUTO_UPDATE"] = "1" }
          .start().apply { outputStream.close(); waitFor(10, TimeUnit.MINUTES) }
      }
    }, "Phantom AI tmux install").apply { isDaemon = true }.start()
  }

  /** [command] in the plain shell; when the agent exits a login shell stays, as in tmux. */
  fun plain(command: String): String = "$command; exec \"\${SHELL:-/bin/zsh}\" -l"

  /**
   * The process the terminal starts: the user's shell running [line] (-l -i: the profile and rc files set PATH, node
   * managers and the like). The line is the process's argument, never typed into a prompt, so it does not show on screen.
   */
  fun shellCommand(line: String): List<String> = listOf(System.getenv("SHELL")?.ifBlank { null } ?: "/bin/zsh", "-l", "-i", "-c", line)

  /**
   * The shell command that runs [command] in a new tmux session [name] in [cwd], with [env] set in it (a running tmux
   * server does not take the client's environment), mouse mode on and no status line. When the agent exits the session
   * keeps a login shell, as the plain terminal does. destroy-unattached is forced off: a user's ~/.tmux.conf must not end
   * the agent when its window closes and leaves it detached.
   */
  fun wrap(tmux: String, name: String, cwd: String, env: Map<String, String>, command: String): String {
    val inner = plain(command)
    val args = buildList {
      add(tmux); add("-L"); add(SOCKET); add("new-session"); add("-s"); add(name); add("-c"); add(cwd)
      env.toSortedMap().forEach { (k, v) -> add("-e"); add("$k=$v") }
      add(inner)
      add(";"); add("set-option"); add("-t"); add(name); add("mouse"); add("on")
      add(";"); add("set-option"); add("-t"); add(name); add("status"); add("off")
      add(";"); add("set-option"); add("-t"); add(name); add("destroy-unattached"); add("off")
    }
    return "exec " + args.joinToString(" ") { quote(it) }
  }

  /** The shell command that shows the running session [name] (its agent kept running while no window showed it). */
  fun attach(tmux: String, name: String): String =
    "exec " + listOf(tmux, "-L", SOCKET, "attach-session", "-t", name).joinToString(" ") { quote(it) }

  /** Ends session [name]; the agent goes with its tab. Best effort, bounded. */
  fun kill(tmux: String, name: String) {
    run(tmux, "kill-session", "-t", name)
  }

  /**
   * Ends the sessions [names] at once, each by its own `kill-session` (a tmux command list stops at the first session
   * that is already gone), within 3 seconds in all; stragglers are killed. Never the whole server: other Phantom
   * instances share the socket. Best effort.
   */
  fun killAll(tmux: String, names: Collection<String>) {
    val started = names.mapNotNull { name ->
      runCatching {
        ProcessBuilder(tmux, "-L", SOCKET, "kill-session", "-t", name).redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().apply { outputStream.close() }
      }.getOrNull()
    }
    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    for (p in started) {
      val ended = runCatching { p.waitFor(maxOf(0, end - System.nanoTime()), TimeUnit.NANOSECONDS) }.getOrDefault(false)
      if (!ended) p.destroyForcibly()
    }
  }

  /**
   * Whether session [name] still runs on Phantom's server: true, false (tmux said it has no such session: exit code 1),
   * or null when that is unknown (tmux failed to run, timed out or answered something else). Bounded.
   */
  fun hasSession(tmux: String, name: String): Boolean? = when (run(tmux, "has-session", "-t", name)) {
    0 -> true
    1 -> false
    else -> null
  }

  /** Exit code of `tmux -L phantom [args]`; null if it failed to run or did not end within 3 seconds (then it is killed). */
  private fun run(tmux: String, vararg args: String): Int? = runCatching {
    ProcessBuilder(tmux, "-L", SOCKET, *args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().run {
      outputStream.close()
      if (waitFor(3, TimeUnit.SECONDS)) exitValue() else null.also { destroyForcibly() }
    }
  }.getOrNull()

  /** The tmux calls made on agents' sessions after they started; [replaceSessions] fakes them in tests, which need no tmux. */
  @Volatile var sessions: TmuxSessions = object : TmuxSessions {
    // without the binary no window can attach it any more: as good as gone
    override fun exists(name: String): Boolean? = binary()?.let { hasSession(it, name) } ?: false
    override fun kill(name: String) {
      binary()?.let { kill(it, name) }
    }
    override fun killAll(names: Collection<String>) {
      if (names.isNotEmpty()) binary()?.let { killAll(it, names) }
    }
  }
    private set

  /** Replaces [sessions]; returns the previous one, to put back. */
  @TestOnly fun replaceSessions(next: TmuxSessions): TmuxSessions = sessions.also { sessions = next }
}

/** Phantom's tmux server, as far as a running agent's session is concerned. The calls block (bounded): keep them short on the EDT. */
interface TmuxSessions {
  /** Whether session [name] still runs; null when that could not be told (tmux failed or timed out). */
  fun exists(name: String): Boolean?

  /** Ends session [name] and the agent in it. Best effort. */
  fun kill(name: String)

  /** Ends the sessions [names] together, bounded in all (the IDE quits or the plugin unloads). Best effort. */
  fun killAll(names: Collection<String>)
}

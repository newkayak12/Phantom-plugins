// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.ShutDownTracker
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.core.Unloading
import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.mcp.McpConfigFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agents whose project window closed keep running in tmux ([dev.phantom.ai.core.AgentSession.detached]) until a window
 * reattaches them. Quitting the IDE or unloading this plugin ends them: no tmux session this IDE started outlives it, and
 * a reloaded plugin starts with an empty session store that could never reach them.
 */
object DetachedSessions {
  const val PLUGIN_ID = "dev.phantom.ai"

  private val shutdownHooked = AtomicBoolean()
  private val shutdownTask = Runnable { killOwned() }

  /** The tmux sessions this IDE started and has not ended, attached or detached: only these are ever ended at once. */
  private val owned: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /** The scheduled tasks of open tabs (binding a hook-less session's file): cancelled when the plugin unloads. */
  private val tasks: MutableSet<Future<*>> = ConcurrentHashMap.newKeySet()

  /** [name] is a tmux session this IDE started. */
  fun own(name: String) {
    owned += name
  }

  /** [name] has ended (its tab closed, or tmux said it is gone). */
  fun disown(name: String) {
    owned -= name
  }

  /** Keeps [future] to cancel on unload; false (and it is cancelled now) when the plugin is already unloading. */
  fun track(future: Future<*>): Boolean {
    if (unloading()) {
      future.cancel(false)
      return false
    }
    tasks += future
    return true
  }

  fun untrack(future: Future<*>) {
    tasks -= future
  }

  /** Ends every tmux session this IDE started, at once and bounded. The JVM shutdown runs it; so does [unload]. */
  internal fun killOwned() {
    val names = owned.toList()
    owned.removeAll(names.toSet())
    if (names.isNotEmpty()) Tmux.sessions.killAll(names)
  }

  /**
   * Whether the IDE is quitting. Read live from the application: a quit cancelled (a project refusing to close, a
   * dialog answered No) resets it, so windows closed afterwards detach again.
   */
  fun exiting(): Boolean = ApplicationManager.getApplication()?.isExitInProgress == true

  /** Whether this plugin is being unloaded (disabled or updated without a restart). */
  fun unloading(): Boolean = Unloading.inProgress

  /** Whether a closing project window may leave its agents running: not while the IDE quits or the plugin unloads. */
  fun mayDetach(): Boolean = !exiting() && !unloading()

  /**
   * Called once an agent runs in tmux: the JVM's shutdown (the quit is certain by then) ends Phantom's tmux server,
   * attached and detached agents alike. Not in unit tests, which must never touch a real tmux server.
   */
  fun hookShutdown() {
    // never after unload: the task would keep this plugin's classes referenced
    if (unloading() || ApplicationManager.getApplication()?.isUnitTestMode != false) return
    if (shutdownHooked.compareAndSet(false, true)) ShutDownTracker.getInstance().registerShutdownTask(shutdownTask)
  }

  /**
   * This plugin is unloading (its services still answer): everything the tabs would do when disposed is done here, as
   * they will run with the classloader going and must look nothing up. Tasks are cancelled, hook-less sessions get their
   * file bound for a later resume, every tmux session this IDE started ends (attached and detached alike), and every
   * session leaves the store with its MCP config. The shutdown hook goes too.
   */
  fun unload() {
    Unloading.start()
    if (shutdownHooked.compareAndSet(true, false)) ShutDownTracker.getInstance().unregisterShutdownTask(shutdownTask)
    tasks.forEach { it.cancel(false) }
    tasks.clear()
    bestEffort {
      ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }?.let { AgentTerminalService.getInstance(it).bindOpenSessions() }
    }
    killOwned()
    val store = SessionStore.getInstance()
    store.all().forEach { McpConfigFiles.delete(it.tabId) }
    store.clearSilently()
  }

  /** The sessions of [sessions] that detached from the window of the project at [basePath]. */
  fun detachedFrom(sessions: Collection<AgentSession>, basePath: String): List<AgentSession> {
    // no file system lookup while nothing is detached (always so when the IDE starts)
    if (sessions.none { it.detached }) return emptyList()
    val base = normalize(basePath)
    return sessions.filter { s -> s.detached && s.detachedFrom?.let { normalize(it) == base } == true }
  }

  /** [path] comparable to another: its real path when it exists, else normalized. */
  fun normalize(path: String): Path {
    val p = Path.of(path)
    return runCatching { p.toRealPath() }.getOrElse { p.toAbsolutePath().normalize() }
  }

  @TestOnly fun resetUnloading() {
    Unloading.reset()
  }

  @TestOnly fun owned(): Set<String> = owned.toSet()

  @TestOnly fun resetOwned() {
    owned.clear()
  }

  @TestOnly fun liveTasks(): Int = tasks.count { !it.isDone }
}

/** Unloading this plugin ends its detached agents ([DetachedSessions.unload]), before the projects' tabs are disposed. */
class DetachedSessionsUnload : DynamicPluginListener {
  override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
    if (pluginDescriptor.pluginId.idString == DetachedSessions.PLUGIN_ID) DetachedSessions.unload()
  }
}

/**
 * A project window opens: the agents that detached from a window of this project come back as tabs, without taking the
 * focus. Only the session store is looked at until there is one: the terminal service and the EDT are left alone at startup.
 */
class DetachedSessionsStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    val base = project.basePath ?: return
    if (DetachedSessions.detachedFrom(SessionStore.getInstance().all(), base).isEmpty()) return
    withContext(Dispatchers.EDT) {
      if (!project.isDisposed) AgentTerminalService.getInstance(project).reattachDetached(base)
    }
  }
}

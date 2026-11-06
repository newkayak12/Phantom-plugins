// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.ShutDownTracker
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.mcp.McpConfigFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agents whose project window closed keep running in tmux ([dev.phantom.ai.core.AgentSession.detached]) until a window
 * reattaches them. Quitting the IDE or unloading this plugin ends them: no Phantom tmux session outlives the IDE, and a
 * reloaded plugin starts with an empty session store that could never reach them.
 */
object DetachedSessions {
  const val PLUGIN_ID = "dev.phantom.ai"

  @Volatile private var unloading = false
  private val shutdownHooked = AtomicBoolean()
  private val shutdownTask = Runnable { Tmux.sessions.killServer() }

  /**
   * Whether the IDE is quitting. Read live from the application: a quit cancelled (a project refusing to close, a
   * dialog answered No) resets it, so windows closed afterwards detach again.
   */
  fun exiting(): Boolean = ApplicationManager.getApplication()?.isExitInProgress == true

  /** Whether this plugin is being unloaded (disabled or updated without a restart). */
  fun unloading(): Boolean = unloading

  /** Whether a closing project window may leave its agents running: not while the IDE quits or the plugin unloads. */
  fun mayDetach(): Boolean = !exiting() && !unloading

  /**
   * Called once an agent runs in tmux: the JVM's shutdown (the quit is certain by then) ends Phantom's tmux server,
   * attached and detached agents alike. Not in unit tests, which must never touch a real tmux server.
   */
  fun hookShutdown() {
    if (ApplicationManager.getApplication()?.isUnitTestMode != false) return
    if (shutdownHooked.compareAndSet(false, true)) ShutDownTracker.getInstance().registerShutdownTask(shutdownTask)
  }

  /**
   * This plugin is unloading: tabs closing from now on kill rather than detach, and every detached agent ends now, its
   * session dropped. The shutdown hook goes too: its class is about to be unloaded.
   */
  fun unload() {
    unloading = true
    if (shutdownHooked.compareAndSet(true, false)) ShutDownTracker.getInstance().unregisterShutdownTask(shutdownTask)
    val store = SessionStore.getInstance()
    for (session in store.all().filter { it.detached }) {
      session.tmuxSession?.let { Tmux.sessions.kill(it) }
      store.remove(session.tabId)
      McpConfigFiles.delete(session.tabId)
    }
  }

  /** [path] comparable to another: its real path when it exists, else normalized. */
  fun normalize(path: String): Path {
    val p = Path.of(path)
    return runCatching { p.toRealPath() }.getOrElse { p.toAbsolutePath().normalize() }
  }

  @TestOnly fun resetUnloading() {
    unloading = false
  }
}

/** Unloading this plugin ends its detached agents ([DetachedSessions.unload]), before the projects' tabs are disposed. */
class DetachedSessionsUnload : DynamicPluginListener {
  override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
    if (pluginDescriptor.pluginId.idString == DetachedSessions.PLUGIN_ID) DetachedSessions.unload()
  }
}

/** A project window opens: the agents that detached from a window of this project come back as tabs, without taking the focus. */
class DetachedSessionsStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    val base = project.basePath ?: return
    withContext(Dispatchers.EDT) {
      if (!project.isDisposed) AgentTerminalService.getInstance(project).reattachDetached(base)
    }
  }
}

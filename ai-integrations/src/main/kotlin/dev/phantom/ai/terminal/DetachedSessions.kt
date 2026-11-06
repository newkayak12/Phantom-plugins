// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.mcp.McpConfigFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly

/**
 * Agents whose project window closed keep running in tmux ([dev.phantom.ai.core.AgentSession.detached]) until a window
 * of their worktree reattaches them or the IDE exits, which ends them: no Phantom tmux session outlives the IDE.
 */
object DetachedSessions {
  @Volatile private var exiting = false

  /** Whether the IDE is quitting: tabs closing now end their agents rather than detach them. */
  fun exiting(): Boolean = exiting || ApplicationManager.getApplication()?.isExitInProgress == true

  /** IDE exit: from now on closing tabs kill their agents; every detached agent is ended now and its session dropped. */
  fun exit() {
    exiting = true
    val store = SessionStore.getInstance()
    for (session in store.all().filter { it.detached }) {
      session.tmuxSession?.let { Tmux.sessions.kill(it) }
      store.remove(session.tabId)
      McpConfigFiles.delete(session.tabId)
    }
  }

  @TestOnly fun resetExiting() {
    exiting = false
  }
}

/** IDE exit ends the detached agents ([DetachedSessions.exit]); before the projects close, so their tabs kill too. */
class DetachedSessionsExit : AppLifecycleListener {
  override fun appWillBeClosed(isRestart: Boolean) = DetachedSessions.exit()
}

/** A project window opens: its worktree's detached agents come back as tabs, without taking the focus. */
class DetachedSessionsStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    val base = project.basePath ?: return
    withContext(Dispatchers.EDT) {
      if (!project.isDisposed) AgentTerminalService.getInstance(project).reattachDetached(base)
    }
  }
}

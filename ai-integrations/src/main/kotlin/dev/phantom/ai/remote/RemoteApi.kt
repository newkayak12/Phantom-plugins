// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.remote

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.sidebar.GitProjectRepos
import dev.phantom.ai.sidebar.SharedWorktrees
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.SidebarModel
import dev.phantom.ai.terminal.AgentTabsHost
import dev.phantom.ai.terminal.AgentTerminalService
import dev.phantom.ai.terminal.TerminalIo
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** What the remote server needs of the IDE. Fakeable; the real one is [IdeRemoteApi]. Every call may block (call off the EDT). */
interface RemoteApi {
  /** Every open project with its repositories, worktrees and sessions. */
  fun state(): RemoteState

  /** Whether [path] is a worktree of an open project's repositories. */
  fun isListedWorktree(path: String): Boolean

  /** Starts a new agent of [kind] in [worktree]; the new tab id, or null when it did not start. */
  fun launch(worktree: String, kind: AgentKind): String?

  /** Resumes the session of [tabId]; the tab id to watch, or null when that session is unknown or cannot be resumed. */
  fun resume(tabId: String): String?

  /** The terminal of an open tab, or null once it is gone. */
  fun terminal(tabId: String): TerminalIo?
}

/** [RemoteApi] over the open projects: the sidebar model per project, launch and resume through [AgentTerminalService] on the EDT. */
class IdeRemoteApi : RemoteApi {
  private val noPr = PrTitles { _, _ -> null }
  private val worktrees = ConcurrentHashMap<String, Project>()
  private val sessions = ConcurrentHashMap<String, Pair<Project, AgentSession>>()

  private fun projects(): List<Project> =
    ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault }

  override fun state(): RemoteState {
    val aiState = AiState.getInstance()
    val live = SessionStore.getInstance().all()
    val saved = aiState.state.sessions.mapNotNull(SidebarModel::saved)
    val now = System.currentTimeMillis()
    val wt = HashMap<String, Project>()
    val se = HashMap<String, Pair<Project, AgentSession>>()
    val infos = projects().map { project ->
      val model = SidebarModel.build(
        GitProjectRepos(project).roots(), SharedWorktrees, live, saved, noPr,
        { p -> aiState.state.worktrees.firstOrNull { it.path == p }?.userName }, now,
      )
      val tabs = EdtCalls.read { AgentTabsHost.getInstance(project).tabs().map { it.id }.toSet() }
      ProjectInfo(
        project.name, project.basePath,
        model.repos.map { r ->
          RepoInfo(
            r.name, r.path,
            r.worktrees.map { w ->
              wt[w.path] = project
              WorktreeInfo(
                w.path, w.title, w.branch,
                w.sessions.map { row ->
                  se[row.session.tabId] = project to row.session
                  SessionInfo(row.session.tabId, row.title, row.agentIconKey, row.statusKey, row.session.tabId in tabs)
                },
              )
            },
          )
        },
      )
    }
    worktrees.clear(); worktrees.putAll(wt)
    sessions.clear(); sessions.putAll(se)
    return RemoteState(infos)
  }

  override fun isListedWorktree(path: String): Boolean {
    if (worktrees.containsKey(path)) return true
    state()
    return worktrees.containsKey(path)
  }

  override fun launch(worktree: String, kind: AgentKind): String? {
    val project = worktrees[worktree] ?: return null
    return EdtCalls.act { AgentTerminalService.getInstance(project).launch(worktree, kind) }
  }

  override fun resume(tabId: String): String? {
    val (project, session) = sessions[tabId] ?: run { state(); sessions[tabId] } ?: return null
    return EdtCalls.act { AgentTerminalService.getInstance(project).resume(session) }
  }

  override fun terminal(tabId: String): TerminalIo? =
    EdtCalls.read { projects().firstNotNullOfOrNull { AgentTabsHost.getInstance(it).get(tabId)?.io } }
}

/**
 * The remote server's calls onto the EDT. invokeAndWait with the default modality waits for every modal dialog to close,
 * so a dialog left open in the IDE hung the remote client and the server thread with it.
 */
internal object EdtCalls {
  private const val ACT_TIMEOUT_MS = 30_000L

  /** Reads UI state (no change) on the EDT, also while a modal dialog is open. */
  fun <T> read(block: () -> T): T {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) return block()
    var result: Result<T>? = null
    app.invokeAndWait({ result = runCatching(block) }, ModalityState.any())
    return result!!.getOrThrow()
  }

  /**
   * Changes the IDE (starts a terminal) on the EDT, outside modal dialogs. Null when it could not start within
   * [timeoutMs]; it then never runs late, so the remote client is not answered null for an agent that starts after all.
   */
  fun <T> act(timeoutMs: Long = ACT_TIMEOUT_MS, block: () -> T?): T? {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) return block()
    val claimed = AtomicBoolean()
    val future = CompletableFuture<T?>()
    app.invokeLater {
      if (claimed.compareAndSet(false, true)) runCatching(block).fold(future::complete, future::completeExceptionally)
    }
    try {
      return future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (ignored: TimeoutException) {
      // not started yet: it never will; started meanwhile: its answer is waited for
      if (claimed.compareAndSet(false, true)) return null
      return future.join()
    } catch (e: ExecutionException) {
      throw e.cause ?: e
    }
  }
}

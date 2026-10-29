// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.api

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.AiStateListener
import dev.phantom.ai.core.SessionListener
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.sidebar.GitProjectRepos
import dev.phantom.ai.sidebar.GitWorktreeSource
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.ProjectRepos
import dev.phantom.ai.sidebar.SidebarModel
import dev.phantom.ai.sidebar.WorktreeSource
import dev.phantom.ai.terminal.AgentTerminalService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * [PhantomAiApi] over [SessionStore], [AiState], [AgentTerminalService] and the sidebar's worktree model. Worktrees are
 * built with a no-PR [PrTitles] (as RemoteApi does), so nothing here touches the network.
 *
 * The worktree watcher polls the worktrees of open projects every [pollIntervalMs] on a pooled thread, only while a listener
 * exists, and fires the listeners once per poll that saw a difference; the first poll only takes the baseline.
 * [pollIntervalMs] <= 0 schedules nothing: tests call [pollWorktreesNow].
 */
@Service(Service.Level.APP)
internal class PhantomAiApiImpl(
  private val reposOf: (Project) -> ProjectRepos = { GitProjectRepos(it) },
  private val sourceOf: (Project) -> WorktreeSource = { GitWorktreeSource(it) },
  private val projects: () -> List<Project> = { ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault } },
  private val pollIntervalMs: Long = DEFAULT_POLL_MS,
) : PhantomAiApi, Disposable {
  private val noPr = PrTitles { _, _ -> null }
  private val listeners = CopyOnWriteArrayList<AiChangeListener>()
  private var watcher: ScheduledFuture<*>? = null
  private var snapshot: Map<String, Set<Triple<String, String?, Boolean>>>? = null

  override fun sessions(): List<AiSession> = SessionStore.getInstance().all().map(ApiMapping::session)

  override fun sessions(project: Project): List<AiSession> =
    ApiMapping.sessionsIn(SessionStore.getInstance().all(), worktrees(project))

  override fun worktrees(project: Project): List<AiWorktree> {
    val aiState = AiState.getInstance()
    val model = SidebarModel.build(
      reposOf(project).roots(), sourceOf(project), emptyList(), emptyList(), noPr,
      { p -> aiState.findWorktree(p)?.userName }, System.currentTimeMillis(),
    )
    return ApiMapping.worktrees(model)
  }

  override fun addChangeListener(parent: Disposable, listener: AiChangeListener) {
    if (Disposer.isDisposed(parent)) return
    val bus = ApplicationManager.getApplication().messageBus.connect(parent)
    bus.subscribe(SessionStore.TOPIC, SessionListener { _, _ -> listener.changed() })
    bus.subscribe(AiState.TOPIC, AiStateListener { listener.changed() })
    listeners.add(listener)
    startWatcher()
    Disposer.register(parent) {
      listeners.remove(listener)
      if (listeners.isEmpty()) stopWatcher()
    }
  }

  override fun focusSession(project: Project, tabId: String): Boolean =
    onEdt(false) { AgentTerminalService.getInstance(project).focus(tabId) }

  override fun startSession(project: Project, worktreePath: String, agent: AiAgent): String? {
    val kind = when (agent) {
      AiAgent.CLAUDE -> dev.phantom.ai.core.AgentKind.CLAUDE
      AiAgent.CODEX -> dev.phantom.ai.core.AgentKind.CODEX
    }
    return onEdt(null) { AgentTerminalService.getInstance(project).launch(worktreePath, kind) }
  }

  /** Takes a worktree snapshot of every open project and fires the listeners once when it differs from the last one. Blocking. */
  internal fun pollWorktreesNow() {
    if (listeners.isEmpty()) return
    val next = HashMap<String, Set<Triple<String, String?, Boolean>>>()
    for (project in projects()) {
      val set = bestEffort { worktrees(project).mapTo(HashSet()) { Triple(ApiMapping.normalize(it.path), it.branch, it.isMain) } }
        .getOrNull() ?: continue
      next[project.locationHash] = set
    }
    val changed = synchronized(this) {
      if (listeners.isEmpty()) return
      val before = snapshot
      snapshot = next
      before != null && before != next
    }
    if (changed) listeners.forEach { bestEffort { it.changed() } }
  }

  internal fun isWatching(): Boolean = synchronized(this) { watcher != null }

  @Synchronized
  private fun startWatcher() {
    if (watcher != null || pollIntervalMs <= 0) return
    watcher = AppExecutorUtil.getAppScheduledExecutorService()
      .scheduleWithFixedDelay({ bestEffort { pollWorktreesNow() } }, 0, pollIntervalMs, TimeUnit.MILLISECONDS)
  }

  @Synchronized
  private fun stopWatcher() {
    watcher?.cancel(false)
    watcher = null
    snapshot = null
  }

  override fun dispose() {
    listeners.clear()
    stopWatcher()
  }

  /** Runs [block] now on the EDT; from another thread queues it with invokeLater (never blocks) and returns [queued]. */
  private fun <T> onEdt(queued: T, block: () -> T): T {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) return block()
    app.invokeLater { bestEffort { block() } }
    return queued
  }

  companion object {
    const val DEFAULT_POLL_MS = 30_000L

    fun instance(): PhantomAiApiImpl = ApplicationManager.getApplication().getService(PhantomAiApiImpl::class.java)
  }
}

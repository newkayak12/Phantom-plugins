// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.phantom.ai.accounts.AccountsService
import dev.phantom.ai.accounts.AgentAccount
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AiNotify
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.core.SystemDirs
import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.core.rethrowIfCancellation
import dev.phantom.ai.hooks.HookEnv
import dev.phantom.ai.hooks.HookServer
import dev.phantom.ai.launch.LaunchCommand
import dev.phantom.ai.launch.McpLink
import dev.phantom.ai.mcp.McpConfigFiles
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Agent terminals as closable tabs of the "Phantom AI Integration" panel ([AgentTabsHost]; never the Terminal tool window).
 * Starting an agent only opens a terminal: no project, no window. All entry points run on the EDT.
 */
@Service(Service.Level.PROJECT)
class AgentTerminalService(private val project: Project) {
  private val host get() = AgentTabsHost.getInstance(project)
  /** Set by tests; else the starter of the backend chosen in the settings, looked up for each terminal. */
  @Volatile private var replacedStarter: TerminalStarter? = null
  private val starter: TerminalStarter
    get() = replacedStarter ?: (TerminalBackends.current()?.starter(project) ?: LocalTerminalStarter(project))

  @TestOnly fun replaceStarter(next: TerminalStarter) {
    replacedStarter = next
  }

  /**
   * Starts a new agent in [worktreePath], the tab titled by [label], else the worktree's branch, else its folder, working
   * on [prompt] from the start when given; returns the tab id, or null.
   */
  @RequiresEdt
  fun launch(worktreePath: String, kind: AgentKind, label: String? = null, prompt: String? = null): String? =
    open(worktreePath, kind, { LaunchCommand.launch(kind, it, prompt, AiState.getInstance().state.skipPermissions) }, sessionId = null, account = AccountsService.getInstance().active(kind), label = label)

  /**
   * Starts the agent resuming [session] (hook session_id, else Claude's earliest-created .jsonl no other session owns); null and nothing run when
   * there is no id. A session whose tab is still open is only focused; a detached one still running is reattached, and
   * one that stays detached (its tmux state unknown, or the terminal failed) runs nothing: never a second agent beside it.
   */
  @RequiresEdt
  fun resume(session: AgentSession): String? {
    if (focus(session.tabId)) return session.tabId
    reattach(session.tabId)?.let { return it }
    if (SessionStore.getInstance().get(session.tabId)?.detached == true) return null
    val account = accountOf(session)
    val id = fallbackTarget(session, account)?.id ?: return null
    bind(session, id)
    return open(session.worktree, session.kind, { LaunchCommand.resume(session.kind, id, it, AiState.getInstance().state.skipPermissions) }, sessionId = id, account = account)
  }

  /** Whether Resume can do anything for [session]; the UI disables the action otherwise. */
  fun canResume(session: AgentSession): Boolean =
    host.get(session.tabId) != null ||
      SessionStore.getInstance().get(session.tabId)?.detached == true ||
      fallbackTarget(session, accountOf(session)) != null

  /** Selects the tab of [tabId], activates the tool window and clears unread; false if there is no such tab. */
  @RequiresEdt
  fun focus(tabId: String): Boolean {
    if (!host.select(tabId)) return false
    toolWindow(create = false)?.activate(null)
    SessionStore.getInstance().update(tabId) { it.copy(unread = false) }
    return true
  }

  /**
   * Shows the detached session [tabId] (its project window closed while the agent kept running in tmux) in a tab here,
   * attached to its tmux session under the same tab id, and returns that id; [activate] = false leaves the tool window
   * alone. Null when it is not detached, when this project's window is closing, or when tmux could not tell whether
   * the session runs (it stays detached). One whose tmux session has ended is dropped (the agent is gone, only --resume
   * can bring the conversation back).
   */
  @RequiresEdt
  fun reattach(tabId: String, activate: Boolean = true): String? = reattach(tabId, activate, probed = false)

  /** [probed]: [probe] said true just before, off the EDT, so tmux is not asked again here. */
  private fun reattach(tabId: String, activate: Boolean, probed: Boolean): String? {
    val store = SessionStore.getInstance()
    val session = store.get(tabId)?.takeIf { it.detached } ?: return null
    // a closing window would dispose the tab at once, killing the agent: leave it for the next window
    if (project.isDisposed || host.isClosed) return null
    if (!probed && probe(session) != true) return null
    val name = session.tmuxSession
    // claimed before the terminal starts: one window at most attaches a session
    var claimed = false
    store.update(tabId) { s -> if (s.detached) s.copy(detached = false, detachedFrom = null).also { claimed = true } else s }
    if (!claimed) return null
    val title = PhantomAiBundle.message("terminal.tab", session.kind.command, branchOf(session.worktree) ?: Path.of(session.worktree).fileName?.toString() ?: session.worktree)
    try {
      show(title, session.worktree, emptyMap(), "", tabId, dev.phantom.ai.core.AgentIcons.of(session.kind), attach = name, activate = activate)
    } catch (e: Exception) {
      // the agent still runs: leave it detached for another try
      store.update(tabId) { it.copy(detached = true, detachedFrom = session.detachedFrom) }
      rethrowIfCancellation(e)
      AiNotify.error(LOG, project, PhantomAiBundle.message("notify.terminal.failed", e.message ?: e.toString()), e)
      return null
    }
    return tabId
  }

  /**
   * Whether the tmux session of the detached [session] still runs; null when tmux could not tell (it stays detached).
   * One that has ended is dropped here, its name killed anyway in case it was reused. Runs tmux: may block for seconds.
   */
  private fun probe(session: AgentSession): Boolean? {
    val name = session.tmuxSession
    val exists = if (name == null) false else Tmux.sessions.exists(name) ?: return null
    if (!exists) {
      name?.let {
        Tmux.sessions.kill(it)
        DetachedSessions.disown(it)
      }
      SessionStore.getInstance().remove(session.tabId)
      McpConfigFiles.delete(session.tabId)
    }
    return exists
  }

  /**
   * Off the EDT: the tab ids of the sessions that detached from a window of the project at [basePath] and still run in
   * tmux, for [reattachDetached]. Ended ones are dropped; ones tmux could not tell about stay detached.
   */
  fun liveDetached(basePath: String): List<String> =
    DetachedSessions.detachedFrom(SessionStore.getInstance().all(), basePath).filter { probe(it) == true }.map { it.tabId }

  /**
   * Reattaches, without activating the tool window, the detached sessions [tabIds] that [liveDetached] found running:
   * reopening a window brings back the agents it had, and tmux is not asked on the EDT.
   */
  @RequiresEdt
  fun reattachDetached(tabIds: Collection<String>) {
    tabIds.forEach { reattach(it, activate = false, probed = true) }
  }

  /** Resume target of [session]: its hook id, else Claude's earliest-created .jsonl that no other session of the worktree owns. */
  private fun fallbackTarget(session: AgentSession, account: AgentAccount?): ResumeTarget.Id? =
    ResumeTarget.forSession(
      session.kind, session.sessionId, session.worktree, claudeConfigDir(account), claimedIds(session), session.startedAt,
    ) as? ResumeTarget.Id

  /** Ids other sessions own: every other live session of the worktree and provider, and every saved one. */
  private fun claimedIds(session: AgentSession): Set<String> {
    val live = SessionStore.getInstance().forWorktree(session.worktree)
      .filter { it.tabId != session.tabId && it.kind == session.kind }.mapNotNull { it.sessionId }
    val saved = AiState.getInstance().sessionsOf(session.worktree)
      .filter { it.provider == session.kind.name }.mapNotNull { it.sessionId }
    return (live + saved).filter { it.isNotBlank() }.toSet()
  }

  /** Stores a fallback id on the live [session] and in the saved sessions, so it is fixed and counts as claimed. */
  private fun bind(session: AgentSession, id: String) {
    if (session.sessionId != null) return
    val live = SessionStore.getInstance().update(session.tabId) { it.copy(sessionId = id) } ?: return
    AiState.getInstance().rememberSession(live.worktree, live.kind, id, live.firstPrompt, live.accountId)
  }

  /**
   * Binds every open hook-less session whose .jsonl has appeared, oldest session first so each takes the earliest-created
   * unclaimed file at or after its start and the claims hold across tabs. Idempotent; safe off the EDT.
   */
  fun bindOpenSessions() {
    val pending = SessionStore.getInstance().all().filter { it.sessionId == null && it.kind == AgentKind.CLAUDE }
      .sortedBy { it.startedAt }
    for (session in pending) {
      bestEffort {
        // re-read: an earlier bind in this pass may have changed claims, a hook may have set the id
        val current = SessionStore.getInstance().get(session.tabId)?.takeIf { it.sessionId == null } ?: return@bestEffort
        fallbackTarget(current, accountOf(current))?.let { bind(current, it.id) }
      }.onFailure { LOG.debug("binding the session file failed", it) }
    }
  }

  /** A hook-less tab is closing: its .jsonl is the only trace of the conversation, so bind it before the session goes. */
  private fun bindOnClose(tabId: String) {
    if (SessionStore.getInstance().get(tabId)?.sessionId != null) return
    bindOpenSessions()
  }

  /** The account [session] ran under (null = System default), not the one active now; a removed account counts as default. */
  private fun accountOf(session: AgentSession): AgentAccount? =
    session.accountId?.let { id -> AccountsService.getInstance().accounts(session.kind).firstOrNull { it.id == id } }

  /** [command] gets the tab's link to Phantom's MCP server (null if its config could not be written: the agent runs without it). */
  private fun open(worktree: String, kind: AgentKind, command: (McpLink?) -> String, sessionId: String?, account: AgentAccount?, label: String? = null): String? {
    val tabId = UUID.randomUUID().toString()
    val hookEnv = HookEnv.forTab(worktree, tabId)
    account?.let { AccountsService.getInstance().prepare(kind, Path.of(it.dir)) }
    val env = LaunchCommand.env(kind, account, hookEnv)
    val mcp = bestEffort { McpConfigFiles.link(HookServer.getInstance().port, HookServer.getInstance().token, tabId) }
      .onFailure { LOG.warn("Phantom MCP config for the agent failed", it) }.getOrNull()
    val title = PhantomAiBundle.message("terminal.tab", kind.command, label ?: branchOf(worktree) ?: Path.of(worktree).fileName?.toString() ?: worktree)
    // registered before the shell starts, so an early hook event finds its session
    SessionStore.getInstance().put(AgentSession(tabId, worktree, kind, sessionId = sessionId, accountId = account?.id))
    sessionId?.let { AiState.getInstance().rememberSession(worktree, kind, it, null, account?.id) }
    val shown = try {
      show(title, worktree, env, command(mcp), tabId, dev.phantom.ai.core.AgentIcons.of(kind))
    } catch (e: Exception) {
      try {
        rethrowIfCancellation(e)
      } catch (c: Throwable) {
        SessionStore.getInstance().remove(tabId)
        throw c
      }
      AiNotify.error(LOG, project, PhantomAiBundle.message("notify.terminal.failed", e.message ?: e.toString()), e)
      false
    }
    if (!shown) {
      SessionStore.getInstance().remove(tabId)
      McpConfigFiles.delete(tabId)
    }
    return tabId.takeIf { shown }
  }

  /** [attach] names a running tmux session to show instead of starting [command]; [activate] = false leaves the tool window alone. */
  private fun show(
    title: String, cwd: String, env: Map<String, String>, command: String, tabId: String?, icon: javax.swing.Icon? = null,
    attach: String? = null, activate: Boolean = true,
  ): Boolean {
    val tw = toolWindow(create = true)
    val disposable = Disposer.newDisposable("AI agent terminal $title")
    val started = try {
      if (attach != null) starter.attach(disposable, cwd, attach) else starter.start(disposable, cwd, env, command)
    } catch (e: Throwable) {
      Disposer.dispose(disposable)
      throw e
    }
    val id = tabId ?: UUID.randomUUID().toString()
    val tmux = started.tmuxSession
    // set when the project window closes (not the IDE): the agent keeps running in tmux and its session waits for a reattach
    var detached = false
    val base = project.basePath
    if (tmux != null) {
      DetachedSessions.own(tmux)
      DetachedSessions.hookShutdown()
    }
    tabId?.let {
      if (tmux != null) SessionStore.getInstance().update(it) { s -> s.copy(tmuxSession = tmux) }
      if (SessionStore.getInstance().get(it)?.let(::needsBinding) == true) pollBindings()
      // closing the tab (or the project, outside tmux) disposes it: the session goes with it. Unloading, the plugin did
      // all of that already (DetachedSessions.unload) and no service may be looked up any more
      Disposer.register(disposable) {
        when {
          DetachedSessions.unloading() -> Unit
          detached -> SessionStore.getInstance().update(it) { s -> s.copy(detached = true, detachedFrom = base) }
          else -> {
            bindOnClose(it)
            SessionStore.getInstance().remove(it)
            McpConfigFiles.delete(it)
          }
        }
      }
    }
    if (tmux != null) {
      // the terminal is only tmux's client: the agent ends with its tab unless it was detached. Unloading or quitting,
      // the sessions this IDE started end together (DetachedSessions.unload, the shutdown hook), not one by one here
      Disposer.register(disposable) {
        if (!detached && !DetachedSessions.unloading() && !DetachedSessions.exiting()) {
          DetachedSessions.disown(tmux)
          ApplicationManager.getApplication().executeOnPooledThread { Tmux.sessions.kill(tmux) }
        }
      }
    }
    val detach = if (tabId != null && tmux != null) ({ detached = true }) else null
    host.add(AgentTab(id, title, started.component, started.preferredFocus, started.io, disposable, icon, detach))
    if (activate) tw?.activate(null)
    return true
  }

  /** The branch checked out in [worktree] read off its HEAD file (a worktree's .git is a file pointing at its git dir); null if detached or not git. */
  private fun branchOf(worktree: String): String? = bestEffort {
    val dot = Path.of(worktree).resolve(".git")
    val gitDir = when {
      java.nio.file.Files.isDirectory(dot) -> dot
      java.nio.file.Files.isRegularFile(dot) ->
        java.nio.file.Files.readString(dot).trim().removePrefix("gitdir:").trim().let { dot.parent.resolve(it).normalize() }
      else -> return@bestEffort null
    }
    java.nio.file.Files.readString(gitDir.resolve("HEAD")).trim().takeIf { it.startsWith("ref: refs/heads/") }?.removePrefix("ref: refs/heads/")
  }.getOrNull()

  private fun toolWindow(create: Boolean): ToolWindow? {
    val manager = ToolWindowManager.getInstance(project)
    manager.getToolWindow(TOOL_WINDOW_ID)?.let { return it }
    if (!create) return null
    // plugin.xml registers it; this only covers a manager that did not instantiate extension tool windows
    return manager.registerToolWindow(RegisterToolWindowTask(TOOL_WINDOW_ID, ToolWindowAnchor.RIGHT))
  }

  private fun claudeConfigDir(account: AgentAccount?): Path {
    return Path.of(account?.dir ?: SystemDirs.redirect(AgentKind.CLAUDE) ?: System.getenv("CLAUDE_CONFIG_DIR") ?: (System.getProperty("user.home") + "/.claude"))
  }

  companion object {
    private val LOG = logger<AgentTerminalService>()
    private const val POLL_MS = 3_000L
    const val TOOL_WINDOW_ID = "Phantom AI Integration"

    fun getInstance(project: Project): AgentTerminalService = project.getService(AgentTerminalService::class.java)

    private val binderLock = Any()
    private var binder: ScheduledFuture<*>? = null

    /** A Claude tab open without a hook session id: only its .jsonl can tell which conversation it is. */
    fun needsBinding(s: AgentSession): Boolean = s.kind == AgentKind.CLAUDE && s.sessionId == null && !s.detached

    /**
     * Starts the one poller that looks for the .jsonl of every hook-less Claude tab, of all windows, every few seconds,
     * unless it runs. It stops once none is pending and is cancelled when the plugin unloads (no task may keep this
     * plugin's code referenced). One per tab scanned the same directories once per tab.
     */
    internal fun pollBindings() {
      synchronized(binderLock) {
        if (binder?.isDone == false) return
        lateinit var future: ScheduledFuture<*>
        future = AppExecutorUtil.getAppScheduledExecutorService()
          .scheduleWithFixedDelay({ bindTick(future) }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
        binder = future
        DetachedSessions.track(future)
      }
    }

    private fun bindTick(future: ScheduledFuture<*>) {
      // checked and stopped under the lock: a tab registered meanwhile either is seen here or starts a new poller
      synchronized(binderLock) {
        if (DetachedSessions.unloading() || SessionStore.getInstance().all().none(::needsBinding)) {
          future.cancel(false)
          DetachedSessions.untrack(future)
          return
        }
      }
      bestEffort { ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }?.let { getInstance(it).bindOpenSessions() } }
        .onFailure { LOG.debug("binding the session files failed", it) }
    }

    @TestOnly fun bindingPollerRunning(): Boolean = synchronized(binderLock) { binder?.isDone == false }

    @TestOnly fun bindTickForTest() {
      synchronized(binderLock) { binder }?.let(::bindTick)
    }

    @TestOnly fun stopBindingPoller() {
      synchronized(binderLock) { binder?.let { it.cancel(false); DetachedSessions.untrack(it) } }
    }
  }
}

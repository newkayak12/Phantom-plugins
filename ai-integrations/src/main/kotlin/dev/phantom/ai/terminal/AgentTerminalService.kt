// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
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
import java.util.concurrent.TimeUnit

/**
 * Agent terminals as closable tabs of the "Phantom AI Integration" panel ([AgentTabsHost]; never the Terminal tool window).
 * Starting an agent only opens a terminal: no project, no window. All entry points run on the EDT.
 */
@Service(Service.Level.PROJECT)
class AgentTerminalService(private val project: Project) {
  private val host get() = AgentTabsHost.getInstance(project)
  @Volatile private var starter: TerminalStarter = LocalTerminalStarter(project)

  @TestOnly fun replaceStarter(next: TerminalStarter) {
    starter = next
  }

  /** Starts a new agent in [worktreePath], the tab titled by [label], else the worktree's branch, else its folder; returns the tab id, or null. */
  @RequiresEdt
  fun launch(worktreePath: String, kind: AgentKind, label: String? = null): String? =
    open(worktreePath, kind, { LaunchCommand.launch(kind, it) }, sessionId = null, account = AccountsService.getInstance().active(kind), label = label)

  /**
   * Starts the agent resuming [session] (hook session_id, else Claude's earliest-created .jsonl no other session owns); null and nothing run when
   * there is no id. A session whose tab is still open is only focused.
   */
  @RequiresEdt
  fun resume(session: AgentSession): String? {
    if (focus(session.tabId)) return session.tabId
    val account = accountOf(session)
    val id = fallbackTarget(session, account)?.id ?: return null
    bind(session, id)
    return open(session.worktree, session.kind, { LaunchCommand.resume(session.kind, id, it) }, sessionId = id, account = account)
  }

  /** Whether Resume can do anything for [session]; the UI disables the action otherwise. */
  fun canResume(session: AgentSession): Boolean =
    host.get(session.tabId) != null ||
      fallbackTarget(session, accountOf(session)) != null

  /** Selects the tab of [tabId], activates the tool window and clears unread; false if there is no such tab. */
  @RequiresEdt
  fun focus(tabId: String): Boolean {
    if (!host.select(tabId)) return false
    toolWindow(create = false)?.activate(null)
    SessionStore.getInstance().update(tabId) { it.copy(unread = false) }
    return true
  }

  /** Runs [command] in a new terminal tab titled [title] (e.g. a login); [cwd] defaults to the user's home. */
  @RequiresEdt
  fun runInTerminal(command: String, env: Map<String, String> = emptyMap(), title: String, cwd: String? = null) {
    show(title, cwd ?: System.getProperty("user.home"), env, command, tabId = null)
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

  /** While a hook-less tab is open, looks for its .jsonl every few seconds; stops once bound or when the tab is disposed. */
  private fun pollBinding(tabId: String, parent: Disposable) {
    val future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
      val session = SessionStore.getInstance().get(tabId)
      if (session == null) return@scheduleWithFixedDelay
      if (session.sessionId == null) bindOpenSessions()
    }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
    Disposer.register(parent) { future.cancel(false) }
  }

  /** The account [session] ran under (null = System default), not the one active now; a removed account counts as default. */
  private fun accountOf(session: AgentSession): AgentAccount? =
    session.accountId?.let { id -> AccountsService.getInstance().accounts(session.kind).firstOrNull { it.id == id } }

  /** [command] gets the tab's link to Phantom's MCP server (null if its config could not be written: the agent runs without it). */
  private fun open(worktree: String, kind: AgentKind, command: (McpLink?) -> String, sessionId: String?, account: AgentAccount?, label: String? = null): String? {
    val tabId = UUID.randomUUID().toString()
    val hookEnv = HookEnv.forTab(worktree, tabId)
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

  private fun show(title: String, cwd: String, env: Map<String, String>, command: String, tabId: String?, icon: javax.swing.Icon? = null): Boolean {
    val tw = toolWindow(create = true)
    val disposable = Disposer.newDisposable("AI agent terminal $title")
    val started = try {
      starter.start(disposable, cwd, env, command)
    } catch (e: Throwable) {
      Disposer.dispose(disposable)
      throw e
    }
    val id = tabId ?: UUID.randomUUID().toString()
    tabId?.let {
      // closing the tab (or the project) disposes it: the session goes with it
      if (SessionStore.getInstance().get(it)?.sessionId == null) pollBinding(it, disposable)
      Disposer.register(disposable) {
        bindOnClose(it)
        SessionStore.getInstance().remove(it)
        McpConfigFiles.delete(it)
      }
    }
    host.add(AgentTab(id, title, started.component, started.preferredFocus, started.io, disposable, icon))
    tw?.activate(null)
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
  }
}

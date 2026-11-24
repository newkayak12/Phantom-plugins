// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.usage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import dev.phantom.ai.accounts.AccountsService
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.SessionListener
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.core.Unloading
import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.hooks.HookInstallService
import dev.phantom.ai.hooks.HookServer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class UsageSnapshot(val claude: ClaudeUsage?, val codex: CodexUsage?)

/** Where Codex usage comes from for the active CODEX_HOME. Injected in tests; the real one asks `codex app-server`. */
fun interface UsageSources {
  fun codex(codexHome: String?): CodexUsage?
}

/**
 * Usage only from the agents' own local channels: Claude's from the statusLine payloads Claude Code sends to the hook
 * server (once the user allowed the hooks), Codex's from `codex app-server` account/rateLimits/read. The plugin reads
 * no login tokens and calls no usage web endpoint itself. Codex is asked only once the user has a Codex session
 * ([UsageStarter]) or clicks the widget, and then only while one runs: each look starts a process that may reach
 * OpenAI. [start] and [startCodex] do nothing in unit-test mode, so tests never start Codex; they call [pollOnce].
 */
@Service(Service.Level.APP)
class UsagePoller(
  private val sources: UsageSources = UsageSources(CodexUsageClient()::fetch),
  private val codexHome: () -> String? = {
    AccountsService.getInstance().active(AgentKind.CODEX)?.dir ?: HookInstallService.getInstance().systemDir(AgentKind.CODEX).toString()
  },
  private val now: () -> Long = System::currentTimeMillis,
  private val periodSeconds: Long = 300,
  private val active: () -> Boolean = { ApplicationManager.getApplication().isActive },
  private val codexInUse: () -> Boolean = { SessionStore.getInstance().all().any { it.kind == AgentKind.CODEX } },
  private val codexInstalled: () -> Boolean = { ProcessCodexLauncher.installed() },
) : com.intellij.openapi.Disposable {
  @Volatile private var statusLine: ClaudeUsage? = null
  @Volatile private var codex: CodexUsage? = null
  @Volatile var snapshot: UsageSnapshot = UsageSnapshot(null, null)
    private set
  val listeners = CopyOnWriteArrayList<() -> Unit>()
  private var task: ScheduledFuture<*>? = null
  private var registered = false
  private var started = false

  /** The widget is installed: takes Claude's statusLine payloads once. Starts no timer and no process; no-op in unit-test mode. */
  fun start() {
    if (ApplicationManager.getApplication()?.isUnitTestMode != false) return
    begin()
  }

  /**
   * [start] without the unit-test guard. The statusLine listener goes on the hook server now when it exists, else when
   * it starts ([listenTo]): payloads only come once it runs, so the widget does not create the server at startup.
   */
  @Synchronized
  internal fun begin() {
    if (started) return
    started = true
    serviceIfCreated<HookServer>()?.let(::listenTo)
  }

  /**
   * A Codex session exists or the widget was clicked: the Codex timer starts once; no-op in unit-test mode. The first
   * look waits a little: a Codex session that just started is busy.
   */
  @Synchronized
  fun startCodex() {
    if (ApplicationManager.getApplication()?.isUnitTestMode != false || task != null) return
    task = AppExecutorUtil.getAppScheduledExecutorService()
      .scheduleWithFixedDelay({ if (!Unloading.inProgress) tick() }, FIRST_LOOK_SECONDS, periodSeconds, TimeUnit.SECONDS)
  }

  val isCodexStarted: Boolean @Synchronized get() = task != null

  /** A click on the widget: one look at Codex now, off the EDT, and the timer from then on. */
  fun refreshNow() {
    startCodex()
    ApplicationManager.getApplication().executeOnPooledThread { if (codexInstalled()) pollOnce() }
  }

  /** Takes the statusLine payloads of [server] once this poller has started; called by [HookServer.ensureStarted] too. */
  @Synchronized
  fun listenTo(server: HookServer) {
    if (!started || registered) return
    registered = true
    server.statuslineListeners.add { _, body -> onStatusLine(body) }
  }

  @Synchronized
  fun stop() {
    task?.cancel(false)
    task = null
  }

  /** The app service goes (plugin unload, IDE exit): the timer must not outlive it. */
  override fun dispose() = stop()

  /** A /statusline/claude body. */
  fun onStatusLine(body: String) {
    val u = StatusLineUsage.parse(body, now()) ?: return
    statusLine = u
    publish()
  }

  /** One look at Codex for the active account; a failure becomes null (shown as an em dash). */
  fun pollOnce() {
    codex = bestEffort { sources.codex(codexHome()) }.onFailure { LOG.debug("codex usage failed", it) }.getOrNull()
    publish()
  }

  /**
   * One timer tick. Each look starts a `codex app-server` process, so none while no IDE window is active, no widget
   * shows, no Codex session runs or Codex is not installed; the last value stays shown.
   */
  fun tick() {
    if (!active() || listeners.isEmpty()) return
    if (codexInUse() && codexInstalled()) pollOnce()
  }

  private fun publish() {
    snapshot = UsageSnapshot(statusLine, codex)
    listeners.forEach { l -> bestEffort { l() } }
  }

  companion object {
    private val LOG = logger<UsagePoller>()
    private const val FIRST_LOOK_SECONDS = 15L
    fun getInstance(): UsagePoller = ApplicationManager.getApplication().getService(UsagePoller::class.java)
  }
}

/**
 * Starts the Codex timer of [UsagePoller] with the first Codex session, not with the IDE or the widget: nothing starts
 * Codex for a user who never runs it. Registered on [SessionStore.TOPIC]; a poller no widget created stays uncreated.
 */
class UsageStarter : SessionListener {
  override fun changed(tabId: String, session: AgentSession?) {
    if (!needed(session)) return
    serviceIfCreated<UsagePoller>()?.takeUnless { it.isCodexStarted }?.startCodex()
  }

  companion object {
    fun needed(session: AgentSession?): Boolean = session?.kind == AgentKind.CODEX
  }
}

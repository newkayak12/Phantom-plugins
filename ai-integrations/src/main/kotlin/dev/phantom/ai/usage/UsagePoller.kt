// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.usage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import dev.phantom.ai.accounts.AccountsService
import dev.phantom.ai.core.AgentKind
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
 * no login tokens and calls no usage web endpoint itself. [start] does nothing in unit-test mode, so tests never start
 * Codex; they call [pollOnce].
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
) : com.intellij.openapi.Disposable {
  @Volatile private var statusLine: ClaudeUsage? = null
  @Volatile private var codex: CodexUsage? = null
  @Volatile var snapshot: UsageSnapshot = UsageSnapshot(null, null)
    private set
  val listeners = CopyOnWriteArrayList<() -> Unit>()
  private var task: ScheduledFuture<*>? = null
  private var registered = false
  private var started = false

  /** Starts the timer and the statusLine listener once; no-op in unit-test mode. */
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
    if (task != null) return
    started = true
    serviceIfCreated<HookServer>()?.let(::listenTo)
    task = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ if (!Unloading.inProgress) tick() }, 0, periodSeconds, TimeUnit.SECONDS)
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

  /** One timer tick: nothing while no IDE window is active, as each look starts a `codex app-server` process. */
  fun tick() {
    if (active()) pollOnce()
  }

  private fun publish() {
    snapshot = UsageSnapshot(statusLine, codex)
    listeners.forEach { l -> bestEffort { l() } }
  }

  companion object {
    private val LOG = logger<UsagePoller>()
    fun getInstance(): UsagePoller = ApplicationManager.getApplication().getService(UsagePoller::class.java)
  }
}

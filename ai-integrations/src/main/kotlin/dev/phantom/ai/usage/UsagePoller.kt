// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
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

/** Where usage comes from for the active account dirs ([claudeDir] null = System default). Injected in tests. */
interface UsageSources {
  fun claude(claudeDir: String?): ClaudeUsage?
  fun codex(codexHome: String?): CodexUsage?
}

class RealUsageSources(
  private val credentials: ClaudeCredentials = ClaudeCredentials(),
  private val claudeClient: ClaudeUsageClient = ClaudeUsageClient(),
  private val codexClient: CodexUsageClient = CodexUsageClient(),
) : UsageSources {
  override fun claude(claudeDir: String?): ClaudeUsage? = credentials.readToken(claudeDir)?.let { claudeClient.fetch(it) }
  override fun codex(codexHome: String?): CodexUsage? = codexClient.fetch(codexHome)
}

/**
 * Polls Claude and Codex usage of the active accounts off the EDT, and takes the Claude statusLine payloads too
 * (newest wins). [start] does nothing in unit-test mode, so tests never poll real dirs; they call [pollOnce].
 */
@Service(Service.Level.APP)
class UsagePoller(
  private val sources: UsageSources = RealUsageSources(),
  private val claudeDir: () -> String? = { AccountsService.getInstance().active(AgentKind.CLAUDE)?.dir },
  private val codexHome: () -> String? = {
    AccountsService.getInstance().active(AgentKind.CODEX)?.dir ?: HookInstallService.getInstance().systemDir(AgentKind.CODEX).toString()
  },
  private val now: () -> Long = System::currentTimeMillis,
  private val periodSeconds: Long = 60,
  private val codexEvery: Int = 5,
  private val active: () -> Boolean = { ApplicationManager.getApplication().isActive },
) : com.intellij.openapi.Disposable {
  @Volatile private var statusLine: ClaudeUsage? = null
  @Volatile private var statusLineAt = 0L
  private var ticks = 0
  @Volatile private var claudePoll: ClaudeUsage? = null
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
    statusLineAt = now()
    publish()
  }

  /** One poll of both sources for the active accounts; a failed source becomes null (shown as an em dash). */
  fun pollOnce() {
    pollClaude()
    pollCodex()
    publish()
  }

  /**
   * One timer tick, cheaper than [pollOnce]: nothing while no IDE window is active; Claude only when no statusLine payload
   * came in the last two periods (a running Claude session sends its usage itself); Codex every [codexEvery] ticks, as
   * each look starts a `codex app-server` process.
   */
  fun tick() {
    if (!active()) return
    val n = ticks++
    if (now() - statusLineAt >= periodSeconds * 2_000) pollClaude()
    if (n % codexEvery == 0) pollCodex()
    publish()
  }

  private fun pollClaude() {
    claudePoll = bestEffort { sources.claude(claudeDir()) }.onFailure { LOG.debug("claude usage failed", it) }.getOrNull()
  }

  private fun pollCodex() {
    codex = bestEffort { sources.codex(codexHome()) }.onFailure { LOG.debug("codex usage failed", it) }.getOrNull()
  }

  private fun publish() {
    snapshot = UsageSnapshot(StatusLineUsage.merge(statusLine, claudePoll), codex)
    listeners.forEach { l -> bestEffort { l() } }
  }

  companion object {
    private val LOG = logger<UsagePoller>()
    fun getInstance(): UsagePoller = ApplicationManager.getApplication().getService(UsagePoller::class.java)
  }
}

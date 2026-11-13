// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.hooks

import dev.phantom.ai.core.rethrowIfCancellation
import dev.phantom.ai.core.bestEffort
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.serviceIfCreated
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.core.Unloading
import dev.phantom.ai.mcp.IdeCodeIndex
import dev.phantom.ai.mcp.IdeEditorBridge
import dev.phantom.ai.mcp.PhantomMcp
import org.jetbrains.annotations.TestOnly
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

fun interface StatuslineListener {
  /** [tabId] from the X-Phantom-Tab-Id header / tabId query (may be null); [body] the raw statusline JSON. */
  fun received(tabId: String?, body: String)
}

/**
 * Loopback-only HTTP server for agent hooks. POST only; every request needs the per-run token header.
 * Routes: /hook/claude, /hook/codex, /statusline/claude, and /mcp ([PhantomMcp], which also takes the token as
 * `Authorization: Bearer`). The tab id comes from the X-Phantom-Tab-Id header or ?tabId=.
 */
@Service(Service.Level.APP)
class HookServer : Disposable {
  val token: String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
  private var server: HttpServer? = null
  private var executor: java.util.concurrent.ExecutorService? = null
  val statuslineListeners = CopyOnWriteArrayList<StatuslineListener>()
  @Volatile var mcp: PhantomMcp = PhantomMcp(IdeEditorBridge(), IdeCodeIndex(), dev.phantom.ai.mcp.AgentTools::all) { tab -> tab?.takeUnless { Unloading.inProgress }?.let { SessionStore.getInstance().get(it)?.worktree } }
    @TestOnly set

  val port: Int get() = server?.address?.port ?: 0

  @Synchronized
  fun ensureStarted() {
    if (server != null) return
    // the usage widget's poller listens from the moment payloads can come in
    serviceIfCreated<dev.phantom.ai.usage.UsagePoller>()?.listenTo(this)
    val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    s.createContext("/") { handle(it) }
    // an MCP call may wait on the EDT; hooks keep their own threads meanwhile
    val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "Phantom AI hook server").apply { isDaemon = true } }
    s.executor = pool
    s.start()
    server = s
    executor = pool
  }

  private class Reply(val code: Int, val json: String? = null)

  private fun handle(ex: HttpExchange) {
    ex.use {
      // the plugin is being unloaded: its services are going, so a late hook (an agent that unload just ended) is refused unread
      val reply = if (Unloading.inProgress) Reply(503) else try { respond(ex) } catch (e: Exception) { rethrowIfCancellation(e); Reply(500) }
      if (reply.json == null) {
        ex.sendResponseHeaders(reply.code, -1)
      } else {
        val bytes = reply.json.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json")
        ex.sendResponseHeaders(reply.code, bytes.size.toLong())
        ex.responseBody.write(bytes)
      }
    }
  }

  private fun respond(ex: HttpExchange): Reply {
    if (ex.requestMethod != "POST") return Reply(405)
    if (!authorized(ex)) return Reply(403)
    val body = String(ex.requestBody.readNBytes(MAX_BODY), StandardCharsets.UTF_8)
    val tabId = ex.requestHeaders.getFirst("X-Phantom-Tab-Id") ?: query(ex, "tabId")
    when (ex.requestURI.path) {
      "/hook/claude" -> hook(AgentKind.CLAUDE, tabId, body)
      "/hook/codex" -> hook(AgentKind.CODEX, tabId, body)
      "/statusline/claude" -> statuslineListeners.forEach { l -> bestEffort { l.received(tabId, body) } }
      "/mcp" -> return mcp.handle(body, tabId)?.let { Reply(200, it) } ?: Reply(202)
      else -> return Reply(404)
    }
    return Reply(204)
  }

  /** The token header, or a bearer token (MCP clients); never a browser page of another origin (DNS rebinding). */
  private fun authorized(ex: HttpExchange): Boolean {
    val origin = ex.requestHeaders.getFirst("Origin")
    if (origin != null && !LOCAL_ORIGIN.matches(origin)) return false
    val given = ex.requestHeaders.getFirst(HookEnv.TOKEN_HEADER)
      ?: ex.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
      ?: return false
    return MessageDigest.isEqual(given.toByteArray(StandardCharsets.UTF_8), token.toByteArray(StandardCharsets.UTF_8))
  }

  private fun hook(kind: AgentKind, tabId: String?, body: String) {
    val event = HookEventMapper.map(kind, body) ?: return
    if (tabId != null) HookIngest.apply(tabId, kind, event)
  }

  private fun query(ex: HttpExchange, key: String): String? =
    ex.requestURI.rawQuery?.split('&')?.map { it.split('=', limit = 2) }
      ?.firstOrNull { it[0] == key && it.size == 2 }?.get(1)?.let { java.net.URLDecoder.decode(it, StandardCharsets.UTF_8) }

  override fun dispose() {
    server?.stop(0)
    server = null
    executor?.shutdownNow()
    executor = null
  }

  companion object {
    private const val MAX_BODY = 4 * 1024 * 1024
    private val LOCAL_ORIGIN = Regex("^https?://(127\\.0\\.0\\.1|localhost|\\[::1])(:\\d+)?$")
    fun getInstance(): HookServer = ApplicationManager.getApplication().getService(HookServer::class.java)
  }
}

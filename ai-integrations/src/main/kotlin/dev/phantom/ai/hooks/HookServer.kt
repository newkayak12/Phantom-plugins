// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.hooks

import dev.phantom.ai.core.rethrowIfCancellation
import dev.phantom.ai.core.bestEffort
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.phantom.ai.core.AgentKind
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
 * Routes: /hook/claude, /hook/codex, /statusline/claude. The tab id comes from the X-Phantom-Tab-Id header or ?tabId=.
 */
@Service(Service.Level.APP)
class HookServer : Disposable {
  val token: String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
  private var server: HttpServer? = null
  val statuslineListeners = CopyOnWriteArrayList<StatuslineListener>()

  val port: Int get() = server?.address?.port ?: 0

  @Synchronized
  fun ensureStarted() {
    if (server != null) return
    val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    s.createContext("/") { handle(it) }
    s.executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "Phantom AI hook server").apply { isDaemon = true } }
    s.start()
    server = s
  }

  private fun handle(ex: HttpExchange) {
    ex.use {
      val code = try { respond(ex) } catch (e: Exception) { rethrowIfCancellation(e); 500 }
      ex.sendResponseHeaders(code, -1)
    }
  }

  private fun respond(ex: HttpExchange): Int {
    if (ex.requestMethod != "POST") return 405
    val given = ex.requestHeaders.getFirst(HookEnv.TOKEN_HEADER) ?: return 403
    if (!MessageDigest.isEqual(given.toByteArray(StandardCharsets.UTF_8), token.toByteArray(StandardCharsets.UTF_8))) return 403
    val body = String(ex.requestBody.readNBytes(MAX_BODY), StandardCharsets.UTF_8)
    val tabId = ex.requestHeaders.getFirst("X-Phantom-Tab-Id") ?: query(ex, "tabId")
    when (ex.requestURI.path) {
      "/hook/claude" -> hook(AgentKind.CLAUDE, tabId, body)
      "/hook/codex" -> hook(AgentKind.CODEX, tabId, body)
      "/statusline/claude" -> statuslineListeners.forEach { l -> bestEffort { l.received(tabId, body) } }
      else -> return 404
    }
    return 204
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
  }

  companion object {
    private const val MAX_BODY = 4 * 1024 * 1024
    fun getInstance(): HookServer = ApplicationManager.getApplication().getService(HookServer::class.java)
  }
}

// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http.core.exec

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.net.PlatformHttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import java.io.InputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class HttpResponseModel(
  val status: Int,
  val version: HttpClient.Version,
  val headers: List<Pair<String, String>>,
  val body: ByteArray,
  /** The body was longer than [HttpExecutionService.MAX_BODY] and was cut. */
  val truncated: Boolean,
  val elapsedMs: Long,
  val warnings: List<String>,
) {
  val contentType: String? get() = header("Content-Type")
  fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

/**
 * Sends requests with the JDK client (proxy and trusted certificates from the IDE). Clients are created on the first
 * run and closed after a minute without requests, so an idle IDE keeps no HTTP threads. Cookies live in memory for
 * the project session.
 */
@Service(Service.Level.PROJECT)
class HttpExecutionService(private val project: Project, private val cs: CoroutineScope) {
  private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
  private val clients = ConcurrentHashMap<ClientKey, HttpClient>()
  private var idleJob: Job? = null
  private val inFlight = AtomicInteger()

  /** Values set by response handlers; kept for the session. */
  val globals: MutableMap<String, String> = ConcurrentHashMap()

  private data class ClientKey(val followRedirects: Boolean, val useCookies: Boolean, val connectTimeout: Duration?)

  fun launch(block: suspend CoroutineScope.() -> Unit): Job = cs.launch(Dispatchers.IO, block = block)

  suspend fun send(request: HttpRequestModel): HttpResponseModel {
    val warnings = ArrayList<String>()
    val builder = HttpRequest.newBuilder(request.uri).version(request.version)
    // Without a timeout a server that never answers keeps the tab at "Sending…" until Stop; `# @timeout` overrides it.
    builder.timeout(request.timeout ?: DEFAULT_TIMEOUT)
    for ((name, value) in request.headers) {
      try {
        builder.header(name, value)
      }
      catch (_: IllegalArgumentException) {
        warnings.add("Header '$name' is set by the HTTP client and was not sent")
      }
    }
    if (request.headers.none { it.first.equals("User-Agent", ignoreCase = true) }) builder.header("User-Agent", USER_AGENT)
    builder.method(request.method, publisher(request.body))

    val client = client(ClientKey(request.followRedirects, request.useCookies, request.connectTimeout))
    val started = System.nanoTime()
    inFlight.incrementAndGet()
    try {
      val response = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream()).await()
      val (body, truncated) = response.body().use { readCancellable(it) }
      val elapsed = (System.nanoTime() - started) / 1_000_000
      val headers = response.headers().map().flatMap { (name, values) -> values.map { name to it } }.filter { !it.first.startsWith(":") }
      return HttpResponseModel(response.statusCode(), response.version(), headers, body, truncated, elapsed, warnings)
    }
    finally {
      inFlight.decrementAndGet()
      scheduleIdleClose()
    }
  }

  private fun publisher(parts: List<HttpRequestModel.BodyPart>): HttpRequest.BodyPublisher {
    if (parts.isEmpty()) return HttpRequest.BodyPublishers.noBody()
    val publishers = parts.map {
      when (it) {
        is HttpRequestModel.BodyPart.Text -> HttpRequest.BodyPublishers.ofString(it.text)
        is HttpRequestModel.BodyPart.File -> HttpRequest.BodyPublishers.ofFile(it.path)
      }
    }
    return if (publishers.size == 1) publishers[0] else HttpRequest.BodyPublishers.concat(*publishers.toTypedArray())
  }

  /**
   * The JDK client's body stream blocks in read() and ignores thread interrupts, so Stop closes the stream to end
   * the read instead of waiting for the server to finish a large or slow body.
   */
  private suspend fun readCancellable(input: InputStream): Pair<ByteArray, Boolean> = coroutineScope {
    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        awaitCancellation()
      }
      finally {
        input.close()
      }
    }
    try {
      readLimited(input)
    }
    catch (e: java.io.IOException) {
      ensureActive()
      throw e
    }
    finally {
      closer.cancel()
    }
  }

  private fun readLimited(input: InputStream): Pair<ByteArray, Boolean> {
    val bytes = input.readNBytes(MAX_BODY + 1)
    return if (bytes.size > MAX_BODY) bytes.copyOf(MAX_BODY) to true else bytes to false
  }

  private fun client(key: ClientKey): HttpClient = clients.computeIfAbsent(key) {
    val builder = PlatformHttpClient.clientBuilder()
      .followRedirects(if (key.followRedirects) HttpClient.Redirect.NORMAL else HttpClient.Redirect.NEVER)
    key.connectTimeout?.let(builder::connectTimeout)
    if (key.useCookies) builder.cookieHandler(cookies)
    builder.build()
  }

  @Synchronized
  private fun scheduleIdleClose() {
    idleJob?.cancel()
    idleJob = cs.launch {
      delay(IDLE_CLOSE_MS)
      if (inFlight.get() == 0) closeClients()
    }
  }

  private fun closeClients() {
    for (key in clients.keys.toList()) {
      val client = clients.remove(key) ?: continue
      try {
        client.close()
      }
      catch (e: Exception) {
        LOG.debug(e)
      }
    }
  }

  companion object {
    const val MAX_BODY: Int = 8 * 1024 * 1024
    private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(60)
    private const val IDLE_CLOSE_MS = 60_000L
    private const val USER_AGENT = "Phantom-HTTP-Client"
    private val LOG = logger<HttpExecutionService>()

    fun getInstance(project: Project): HttpExecutionService = project.service()
  }
}

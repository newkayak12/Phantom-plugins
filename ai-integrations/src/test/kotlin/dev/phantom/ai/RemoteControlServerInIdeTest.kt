// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.remote.ProjectInfo
import dev.phantom.ai.remote.RemoteApi
import dev.phantom.ai.remote.RemoteControlServer
import dev.phantom.ai.remote.RemoteState
import dev.phantom.ai.remote.RemoteStatus
import dev.phantom.ai.remote.RemoteToken
import dev.phantom.ai.remote.Run
import dev.phantom.ai.terminal.ScreenSnapshot
import dev.phantom.ai.terminal.TerminalIo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** The remote server on 127.0.0.1 port 0 with a fake [RemoteApi]; raw sockets so every header is under the test's control. */
class RemoteControlServerInIdeTest {
  private class FakeIo : TerminalIo {
    val sent = CopyOnWriteArrayList<String>()
    @Volatile var listener: (() -> Unit)? = null
    @Volatile var text = "hello"
    override fun snapshot() = ScreenSnapshot(listOf(listOf(Run(text))), 80, 24, 0, 0)
    override fun send(text: String) { sent += text }
    override fun onChange(parent: Disposable, listener: () -> Unit) {
      this.listener = listener
      Disposer.register(parent) { this.listener = null }
    }
  }

  private class FakeApi(val io: FakeIo) : RemoteApi {
    val launches = CopyOnWriteArrayList<Pair<String, AgentKind>>()
    val resumes = CopyOnWriteArrayList<String>()
    @Volatile var tabAlive = true
    override fun state() = RemoteState(listOf(ProjectInfo("p", "/tmp/p", emptyList())))
    override fun isListedWorktree(path: String) = path == "/tmp/wt"
    override fun launch(worktree: String, kind: AgentKind): String { launches += worktree to kind; return "tab-new" }
    override fun resume(tabId: String): String? { resumes += tabId; return tabId.takeIf { it == "tab1" } }
    override fun terminal(tabId: String): TerminalIo? = io.takeIf { tabId == "tab1" && tabAlive }
  }

  private class Resp(val status: Int, val headers: Map<String, List<String>>, val body: String) {
    fun header(n: String) = headers[n.lowercase()]?.firstOrNull()
  }

  private lateinit var server: RemoteControlServer
  private lateinit var api: FakeApi
  private lateinit var io: FakeIo
  @Volatile private var token = ""
  @Volatile private var hash: String? = null
  private var port = 0

  @Before
  fun setUp() {
    token = RemoteToken.generate()
    hash = RemoteToken.hash(token)
    io = FakeIo()
    api = FakeApi(io)
    server = RemoteControlServer()
    server.configure({ hash }, api)
    server.start("127.0.0.1", 0)
    val st = server.status()
    assertTrue("status was $st", st is RemoteStatus.Running)
    port = (st as RemoteStatus.Running).url.substringAfterLast(':').toInt()
    assertTrue(port > 0)
  }

  @After
  fun tearDown() {
    server.stop()
  }

  private fun rotate() {
    token = RemoteToken.generate()
    hash = RemoteToken.hash(token)
  }

  private fun cookie(t: String = token) = "phantom_ai_$port=$t"

  private fun readLine(i: InputStream): String? {
    val b = ByteArrayOutputStream()
    while (true) {
      val c = i.read()
      if (c < 0) return if (b.size() == 0) null else b.toString(Charsets.ISO_8859_1)
      if (c == '\n'.code) return b.toString(Charsets.ISO_8859_1).trimEnd('\r')
      b.write(c)
    }
  }

  private fun readHead(i: InputStream): Pair<Int, Map<String, List<String>>> {
    val status = readLine(i)!!.split(' ')[1].toInt()
    val h = HashMap<String, MutableList<String>>()
    while (true) {
      val l = readLine(i) ?: break
      if (l.isEmpty()) break
      val k = l.substringBefore(':').lowercase()
      h.getOrPut(k) { ArrayList() } += l.substringAfter(':').trim()
    }
    return status to h
  }

  private fun http(method: String, path: String, headers: Map<String, String> = emptyMap(), body: String? = null, withHost: Boolean = true): Resp {
    Socket().use { s ->
      s.soTimeout = 10_000
      s.connect(InetSocketAddress("127.0.0.1", port))
      val data = body?.toByteArray()
      val sb = StringBuilder("$method $path HTTP/1.1\r\n")
      if (withHost) sb.append("Host: 127.0.0.1:$port\r\n")
      sb.append("Connection: close\r\n")
      headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
      if (data != null) sb.append("Content-Length: ${data.size}\r\n")
      sb.append("\r\n")
      s.getOutputStream().write(sb.toString().toByteArray())
      if (data != null) {
        try { s.getOutputStream().write(data); s.getOutputStream().flush() } catch (_: java.io.IOException) {}
      }
      val (status, h) = readHead(s.getInputStream())
      // a refused request may be closed with its body unread: the reset can cut the body short
      val rest = try { s.getInputStream().readAllBytes().toString(Charsets.UTF_8) } catch (_: java.io.IOException) { "" }
      return Resp(status, h, rest)
    }
  }

  private fun get(path: String, vararg h: Pair<String, String>) = http("GET", path, h.toMap())
  private fun post(path: String, body: String, vararg h: Pair<String, String>) = http("POST", path, h.toMap() + ("Content-Type" to "application/json"), body)
  private fun origin() = "Origin" to "http://127.0.0.1:$port"

  private class Ws(val socket: Socket, val status: Int, val headers: Map<String, List<String>>) : AutoCloseable {
    private val rnd = SecureRandom()
    fun send(text: String) {
      val p = text.toByteArray()
      val mask = ByteArray(4).also { rnd.nextBytes(it) }
      val out = ByteArrayOutputStream()
      out.write(0x81)
      require(p.size < 126)
      out.write(0x80 or p.size)
      out.write(mask)
      p.forEachIndexed { i, b -> out.write(b.toInt() xor mask[i % 4].toInt()) }
      socket.getOutputStream().write(out.toByteArray()); socket.getOutputStream().flush()
    }

    /** (opcode, payload); null on end of stream. */
    fun read(): Pair<Int, ByteArray>? {
      val i = socket.getInputStream()
      val b0 = i.read(); if (b0 < 0) return null
      val b1 = i.read()
      var len = b1 and 0x7f
      if (len == 126) len = (i.read() shl 8) or i.read()
      else if (len == 127) { var l = 0L; repeat(8) { l = (l shl 8) or i.read().toLong() }; len = l.toInt() }
      return (b0 and 0x0f) to i.readNBytes(len)
    }

    fun readText(): String? {
      while (true) {
        val (op, p) = read() ?: return null
        if (op == 1) return p.toString(Charsets.UTF_8)
        if (op == 8) return null
      }
    }

    /** The close code of the next close frame, else null. */
    fun closeCode(): Int? {
      while (true) {
        val (op, p) = read() ?: return null
        if (op == 8) return if (p.size >= 2) ((p[0].toInt() and 0xff) shl 8) or (p[1].toInt() and 0xff) else 1005
      }
    }

    override fun close() = socket.close()
  }

  private fun ws(tab: String, headers: Map<String, String>, withOrigin: Boolean = false): Ws {
    val s = Socket()
    s.soTimeout = 10_000
    s.connect(InetSocketAddress("127.0.0.1", port))
    val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
    val sb = StringBuilder("GET /ws?tab=$tab HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n")
    sb.append("Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n")
    if (withOrigin) sb.append("Origin: http://127.0.0.1:$port\r\n")
    headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
    sb.append("\r\n")
    s.getOutputStream().write(sb.toString().toByteArray())
    val (status, h) = readHead(s.getInputStream())
    return Ws(s, status, h)
  }

  private val badTokens get() = listOf<String?>(null, "wrong-token", "x")

  @Test
  fun indexWithoutTokenIs401() {
    val r = get("/")
    assertEquals(401, r.status)
    assertEquals("Pair again from Phantom: Settings | Tools | Phantom AI Integration | Remote Control.", r.body)
    assertNull(r.header("set-cookie"))
    assertEquals(401, get("/", "Cookie" to cookie("nope")).status)
    assertEquals(401, get("/no-such-path").status)
  }

  @Test
  fun staticAssetWithoutTokenIs401() {
    for (p in listOf("/vendor/xterm/xterm.js", "/app.js", "/app.css", "/vendor/xterm/xterm.css", "/vendor/xterm/addon-fit.js", "/index.html")) {
      assertEquals(p, 401, get(p).status)
    }
    assertEquals(200, get("/vendor/xterm/xterm.js", "Cookie" to cookie()).status)
    assertEquals(200, get("/app.js", "Cookie" to cookie()).status)
  }

  @Test
  fun apiWithoutOrWrongTokenIs401() {
    for (t in badTokens) {
      val h = if (t == null) emptyArray() else arrayOf("Cookie" to cookie(t))
      val b = if (t == null) emptyArray() else arrayOf("Authorization" to "Bearer $t")
      for (hs in listOf(h, b)) {
        assertEquals(401, get("/api/state", *hs).status)
        assertEquals(401, post("/api/launch", """{"worktree":"/tmp/wt","kind":"claude"}""", *hs).status)
        assertEquals(401, post("/api/resume", """{"tabId":"tab1"}""", *hs).status)
      }
    }
    assertTrue(api.launches.isEmpty())
    assertTrue(api.resumes.isEmpty())
  }

  @Test
  fun wsUpgradeWithoutTokenIs401() {
    ws("tab1", emptyMap()).use { assertEquals(401, it.status) }
    ws("tab1", mapOf("Cookie" to cookie("bad"))).use { assertEquals(401, it.status) }
    ws("tab1", mapOf("Authorization" to "Bearer x")).use { assertEquals(401, it.status) }
    assertTrue(server.clients().isEmpty())
  }

  @Test
  fun pairSetsHttpOnlyStrictCookieAndRedirects() {
    val r = get("/pair?token=$token")
    assertEquals(303, r.status)
    assertEquals("/", r.header("location"))
    val c = r.header("set-cookie")!!
    assertTrue(c, c.startsWith("phantom_ai_$port=$token"))
    for (a in listOf("HttpOnly", "SameSite=Strict", "Path=/", "Max-Age=31536000")) assertTrue("$a in $c", c.contains(a))
    // the cookie it set opens the page
    assertEquals(200, get("/", "Cookie" to c.substringBefore(';')).status)
    val wrong = get("/pair?token=wrong")
    assertEquals(401, wrong.status)
    assertNull(wrong.header("set-cookie"))
    assertEquals(401, get("/pair").status)
    // opened from another site, a redirect would keep that site for a Strict cookie: the page moves on by itself instead
    val cross = get("/pair?token=$token", "Sec-Fetch-Site" to "cross-site")
    assertEquals(200, cross.status)
    assertTrue(cross.header("set-cookie")!!.contains("SameSite=Strict"))
    assertTrue(cross.body.contains("http-equiv=refresh"))
    assertNull(cross.header("location"))
  }

  @Test
  fun cookieOrBearerIsAccepted() {
    val idx = get("/", "Cookie" to cookie())
    assertEquals(200, idx.status)
    assertTrue(idx.body.contains("Phantom AI Integration"))
    assertEquals("no-store", idx.header("cache-control"))
    assertEquals("nosniff", idx.header("x-content-type-options"))
    assertEquals("no-referrer", idx.header("referrer-policy"))
    assertEquals(200, get("/api/state", "Authorization" to "Bearer $token").status)
    assertTrue(get("/api/state", "Cookie" to cookie()).body.contains("\"projects\""))
    // a cookie among others
    assertEquals(200, get("/", "Cookie" to "a=b; ${cookie()}; c=d").status)
    // launch and resume
    val launch = post("/api/launch", """{"worktree":"/tmp/wt","kind":"codex"}""", "Authorization" to "Bearer $token")
    assertEquals(200, launch.status)
    assertTrue(launch.body.contains("tab-new"))
    assertEquals(listOf("/tmp/wt" to AgentKind.CODEX), api.launches.toList())
    assertEquals(200, post("/api/resume", """{"tabId":"tab1"}""", "Authorization" to "Bearer $token").status)
    // malformed JSON, unknown kind, unlisted worktree, unknown tab
    val h = "Authorization" to "Bearer $token"
    assertEquals(400, post("/api/launch", "{not json", h).status)
    assertEquals(400, post("/api/launch", """{"worktree":"/tmp/wt","kind":"vim"}""", h).status)
    assertEquals(400, post("/api/launch", """{"worktree":"/etc","kind":"claude"}""", h).status)
    assertEquals(400, post("/api/resume", """{"tabId":"nope"}""", h).status)
    assertEquals(1, api.launches.size)
  }

  @Test
  fun bodyOverOneMiBIsRejected() {
    val big = "x".repeat(1024 * 1024 + 10)
    val r = runCatching { post("/api/launch", big, "Authorization" to "Bearer $token") }.getOrNull()
    assertTrue(r == null || r.status == 413)
    assertTrue(api.launches.isEmpty())
  }

  @Test
  fun previousTokenAfterRegenerateIs401() {
    val old = token
    ws("tab1", mapOf("Cookie" to cookie()), withOrigin = true).use { w ->
      assertEquals(101, w.status)
      w.send("""{"t":"auth","token":""}""")
      assertNotNull(w.readText())
      rotate()
      assertEquals(401, get("/", "Cookie" to cookie(old)).status)
      assertEquals(401, get("/api/state", "Authorization" to "Bearer $old").status)
      assertEquals(401, post("/api/launch", """{"worktree":"/tmp/wt","kind":"claude"}""", "Cookie" to cookie(old), origin()).status)
      assertEquals(401, get("/pair?token=$old").status)
      ws("tab1", mapOf("Cookie" to cookie(old)), withOrigin = true).use { assertEquals(401, it.status) }
      server.sweepNow()
      assertEquals(4401, w.closeCode())
    }
    assertEquals(200, get("/", "Cookie" to cookie()).status)
    assertTrue(api.launches.isEmpty())
  }

  @Test
  fun crossOriginPostOrWsIs403() {
    val body = """{"worktree":"/tmp/wt","kind":"claude"}"""
    assertEquals(403, post("/api/launch", body, "Cookie" to cookie()).status)
    assertEquals(403, post("/api/launch", body, "Cookie" to cookie(), "Origin" to "http://evil.example").status)
    assertEquals(403, post("/api/launch", body, "Cookie" to cookie(), "Origin" to "http://127.0.0.1:${port + 1}").status)
    assertTrue(api.launches.isEmpty())
    assertEquals(200, post("/api/launch", body, "Cookie" to cookie(), origin()).status)
    // a websocket: no Origin and a foreign Origin are refused, the page's own is not
    ws("tab1", mapOf("Cookie" to cookie())).use { assertEquals(403, it.status) }
    ws("tab1", mapOf("Cookie" to cookie(), "Origin" to "http://evil.example")).use { assertEquals(403, it.status) }
    ws("tab1", mapOf("Cookie" to cookie()), withOrigin = true).use { assertEquals(101, it.status) }
    // a Bearer client is not a browser: no Origin needed
    ws("tab1", mapOf("Authorization" to "Bearer $token")).use { assertEquals(101, it.status) }
  }

  @Test
  fun pathTraversalIs404() {
    val c = "Cookie" to cookie()
    for (p in listOf("/../etc/passwd", "/vendor/xterm/../../app.js", "/%2e%2e/%2e%2e/etc/passwd", "/vendor/xterm/", "/vendor/xterm/nope.js", "/remote-web/app.js", "/app.js/../index.html", "/vendor/%2e%2e/app.js")) {
      assertEquals(p, 404, get(p, c).status)
    }
  }

  @Test
  fun wsStreamsScreenAndTakesInputAndEndsWithTheTab() {
    ws("tab1", mapOf("Cookie" to cookie()), withOrigin = true).use { w ->
      assertEquals(101, w.status)
      w.send("""{"t":"input","data":"ignored"}""") // before auth: dropped
      w.send("""{"t":"auth","token":""}""")
      val first = w.readText()!!
      assertTrue(first, first.contains("\"t\":\"screen\"") && first.contains("hello"))
      assertEquals(1, server.clients().size)
      assertEquals("tab1", server.clients()[0].tabId)
      assertTrue(server.clients()[0].remoteAddress.startsWith("127.0.0.1"))
      w.send("""{"t":"input","data":"ls\r"}""")
      val deadline = System.currentTimeMillis() + 5000
      while (io.sent.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
      assertEquals(listOf("ls\r"), io.sent.toList())
      io.text = "changed"
      io.listener!!.invoke()
      val next = w.readText()!!
      assertTrue(next, next.contains("changed"))
      api.tabAlive = false
      server.sweepNow()
      assertEquals("{\"t\":\"closed\"}", w.readText())
    }
  }

  @Test
  fun wsWithoutFirstMessageIsClosed4401() {
    ws("tab1", mapOf("Cookie" to cookie()), withOrigin = true).use { w ->
      assertEquals(101, w.status)
      assertEquals(4401, w.closeCode())
    }
  }

  @Test
  fun bindsTheGivenAddressAndStops() {
    val s = server.status() as RemoteStatus.Running
    assertEquals("http://127.0.0.1:$port", s.url)
    server.stop()
    assertEquals(RemoteStatus.Stopped, server.status())
    assertFalse(runCatching { Socket("127.0.0.1", port).close() }.isSuccess)
  }
}

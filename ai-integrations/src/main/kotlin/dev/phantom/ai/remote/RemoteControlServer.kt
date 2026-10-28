// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.remote

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.terminal.TerminalIo
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory
import io.netty.util.AttributeKey
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.EventExecutorGroup
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed interface RemoteStatus {
  data class Running(val url: String) : RemoteStatus
  data object Stopped : RemoteStatus
  data class Failed(val message: String) : RemoteStatus
}

/** A connected websocket client. */
data class RemoteClient(val remoteAddress: String, val connectedSince: Long, val tabId: String)

/**
 * The remote control server: HTTP + WebSocket on Netty, serving the bundled web client and the terminals of the open projects.
 * Nothing is served without the pairing token (cookie `phantom_ai_<port>` or `Authorization: Bearer`), checked against the
 * current token hash on every request; see the routes in [Router]. It builds no process command: launch and resume go
 * through [RemoteApi].
 */
@Service(Service.Level.APP)
class RemoteControlServer : Disposable {
  @Volatile private var tokenHash: () -> String? = { RemoteControlSettings.getInstance().state.tokenHash }
  @Volatile private var api: RemoteApi = IdeRemoteApi()

  private var runtime: Runtime? = null
  @Volatile private var failure: String? = null
  private val sessions = ConcurrentHashMap<Channel, WsSession>()

  /** For tests: a token hash source and a [RemoteApi] other than the IDE's. */
  fun configure(tokenHash: () -> String?, api: RemoteApi) {
    this.tokenHash = tokenHash
    this.api = api
  }

  private class Runtime(
    val boss: EventLoopGroup, val workers: EventLoopGroup, val handlers: EventExecutorGroup,
    val timers: ScheduledExecutorService, val channel: Channel, val address: String, val port: Int,
  ) {
    fun shutdown() {
      bestEffort { channel.close().syncUninterruptibly() }
      bestEffort { handlers.shutdownGracefully(0, 1, TimeUnit.SECONDS) }
      bestEffort { workers.shutdownGracefully(0, 1, TimeUnit.SECONDS) }
      bestEffort { boss.shutdownGracefully(0, 1, TimeUnit.SECONDS) }
      timers.shutdownNow()
    }
  }

  fun status(): RemoteStatus {
    val r = runtime
    if (r != null) return RemoteStatus.Running(url(r.address, r.port))
    return failure?.let { RemoteStatus.Failed(it) } ?: RemoteStatus.Stopped
  }

  /** Binds exactly [address]:[port] (port 0 takes a free one); a running server is stopped first. Failure is reported by [status]. */
  @Synchronized
  fun start(address: String, port: Int) {
    stop()
    var boss: EventLoopGroup? = null
    var workers: EventLoopGroup? = null
    var handlers: EventExecutorGroup? = null
    var timers: ScheduledExecutorService? = null
    try {
      boss = NioEventLoopGroup(1, threads)
      workers = NioEventLoopGroup(2, threads)
      handlers = DefaultEventExecutorGroup(4, threads)
      timers = Executors.newSingleThreadScheduledExecutor(threads)
      val holder = arrayOfNulls<Runtime>(1)
      val bootstrap = ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel::class.java)
        .childOption(ChannelOption.TCP_NODELAY, true)
        .childHandler(object : ChannelInitializer<SocketChannel>() {
          override fun initChannel(ch: SocketChannel) {
            val rt = holder[0] ?: run { ch.close(); return }
            ch.pipeline().addLast(HttpServerCodec())
            ch.pipeline().addLast(Gate(rt.port))
            ch.pipeline().addLast(HttpObjectAggregator(MAX_BODY))
            ch.pipeline().addLast(handlers, Router(rt))
          }
        })
      val channel = bootstrap.bind(InetSocketAddress(address, port)).syncUninterruptibly().channel()
      val real = (channel.localAddress() as InetSocketAddress).port
      val rt = Runtime(boss, workers, handlers, timers, channel, address, real)
      holder[0] = rt
      runtime = rt
      failure = null
      timers.scheduleWithFixedDelay({ bestEffort { sweep(rt) } }, SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS)
    } catch (e: Throwable) {
      bestEffort { boss?.shutdownGracefully(0, 1, TimeUnit.SECONDS) }
      bestEffort { workers?.shutdownGracefully(0, 1, TimeUnit.SECONDS) }
      bestEffort { handlers?.shutdownGracefully(0, 1, TimeUnit.SECONDS) }
      timers?.shutdownNow()
      failure = e.message ?: e.javaClass.simpleName
    }
  }

  @Synchronized
  fun stop() {
    val r = runtime
    runtime = null
    failure = null
    sessions.values.toList().forEach { it.close(4400, "stopped") }
    r?.shutdown()
  }

  /** Starts when the settings say enabled and hold a token hash; otherwise stops. Restarts when address or port changed. */
  @Synchronized
  fun applySettings() {
    val s = RemoteControlSettings.getInstance().state
    val hash = tokenHash()
    if (!s.enabled || hash == null) return stop()
    val address = s.address ?: "127.0.0.1"
    val r = runtime
    if (r != null && r.address == address && (s.port == 0 || r.port == s.port)) return
    start(address, s.port)
  }

  fun clients(): List<RemoteClient> = sessions.values.map { RemoteClient(it.remote, it.since, it.tabId) }

  /** For tests: the same sweep that rotation and closed tabs rely on, run now. */
  fun sweepNow() {
    runtime?.let { sweep(it) }
  }

  override fun dispose() = stop()

  private fun url(address: String, port: Int) = "http://${if (address.contains(':')) "[$address]" else address}:$port"

  // ===== websocket sessions =====

  private inner class WsSession(val ch: Channel, val tabId: String, val io: TerminalIo, val credential: String, val rt: Runtime) {
    val since = System.currentTimeMillis()
    val remote: String = ch.remoteAddress()?.toString()?.removePrefix("/") ?: "?"
    @Volatile var authed = false
    private val pending = AtomicBoolean(false)
    private val disposable = Disposer.newDisposable("Phantom AI remote $tabId")
    private var authTimer: ScheduledFuture<*>? = null
    @Volatile private var closed = false

    fun open() {
      sessions[ch] = this
      authTimer = rt.timers.schedule({ if (!authed) close(4401, "unauthorized") }, AUTH_MS, TimeUnit.MILLISECONDS)
      bestEffort { io.onChange(disposable) { frameSoon() } }
    }

    fun frameSoon() {
      if (closed || !authed || !pending.compareAndSet(false, true)) return
      bestEffort { rt.timers.schedule({ pending.set(false); frame() }, THROTTLE_MS, TimeUnit.MILLISECONDS) }
        .onFailure { pending.set(false) }
    }

    fun frame() {
      if (closed) return
      bestEffort {
        val s = io.snapshot()
        val data = ScreenAnsi.render(s.lines, s.cols, s.rows, s.cursorX, s.cursorY)
        ch.writeAndFlush(TextWebSocketFrame(RemoteProtocol.encode(ServerMessage.Screen(s.cols, s.rows, s.cursorX, s.cursorY, data))))
      }
    }

    fun close(code: Int, reason: String) {
      if (closed) return
      closed = true
      sessions.remove(ch)
      authTimer?.cancel(false)
      bestEffort { Disposer.dispose(disposable) }
      if (ch.isActive) ch.writeAndFlush(CloseWebSocketFrame(code, reason)).addListener(ChannelFutureListener.CLOSE)
    }

    fun detach() {
      closed = true
      sessions.remove(ch)
      authTimer?.cancel(false)
      bestEffort { Disposer.dispose(disposable) }
    }
  }

  /** Closes the sessions whose token was rotated and those whose tab is gone. */
  private fun sweep(rt: Runtime) {
    val hash = tokenHash()
    for (s in sessions.values.toList()) {
      if (!RemoteToken.matches(s.credential, hash)) {
        s.close(4401, "unauthorized")
      } else if (api.terminal(s.tabId) !== s.io) {
        bestEffort { s.ch.writeAndFlush(TextWebSocketFrame(RemoteProtocol.encode(ServerMessage.Closed))) }
        s.close(1000, "closed")
      }
    }
  }

  // ===== http =====

  private class Auth(val credential: String, val bearer: Boolean)

  /** First handler: nothing passes without a valid cookie or Bearer token (except /pair, which checks its own query token). */
  private inner class Gate(private val port: Int) : ChannelInboundHandlerAdapter() {
    private var denied = false

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
      if (msg !is HttpRequest) {
        if (denied) ReferenceCountUtil.release(msg) else ctx.fireChannelRead(msg)
        return
      }
      denied = false
      if (msg.decoderResult().isFailure) {
        denied = true
        ReferenceCountUtil.release(msg)
        ctx.close()
        return
      }
      val path = QueryStringDecoder(msg.uri()).path()
      if (path == "/pair") {
        ctx.fireChannelRead(msg)
        return
      }
      val auth = authenticate(msg, port)
      if (auth == null) {
        denied = true
        ReferenceCountUtil.release(msg)
        respond(ctx, text(HttpResponseStatus.UNAUTHORIZED, UNAUTHORIZED_BODY))
        return
      }
      ctx.channel().attr(AUTH).set(auth)
      ctx.fireChannelRead(msg)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
      ctx.close()
    }
  }

  private fun authenticate(req: HttpRequest, port: Int): Auth? {
    val hash = tokenHash()
    req.headers().get(HttpHeaderNames.AUTHORIZATION)?.takeIf { it.startsWith("Bearer ") }?.substring(7)?.trim()?.let {
      if (RemoteToken.matches(it, hash)) return Auth(it, true)
    }
    val name = "phantom_ai_$port"
    for (header in req.headers().getAll(HttpHeaderNames.COOKIE)) {
      for (part in header.split(';')) {
        val kv = part.trim().split('=', limit = 2)
        if (kv.size == 2 && kv[0] == name && RemoteToken.matches(kv[1], hash)) return Auth(kv[1], false)
      }
    }
    return null
  }

  /** Routes of an authenticated (or /pair) request. */
  private inner class Router(private val rt: Runtime) : SimpleChannelInboundHandler<Any>() {
    override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
      when (msg) {
        is FullHttpRequest -> http(ctx, msg)
        is WebSocketFrame -> frame(ctx, msg)
      }
    }

    private fun http(ctx: ChannelHandlerContext, req: FullHttpRequest) {
      val decoder = QueryStringDecoder(req.uri())
      val path = decoder.path()
      if (path == "/pair") return pair(ctx, req, decoder)
      val auth = ctx.channel().attr(AUTH).get() ?: return respond(ctx, text(HttpResponseStatus.UNAUTHORIZED, UNAUTHORIZED_BODY))
      val upgrade = req.headers().get(HttpHeaderNames.UPGRADE)?.equals("websocket", ignoreCase = true) == true
      val unsafe = req.method() != HttpMethod.GET && req.method() != HttpMethod.HEAD
      if (!auth.bearer && (unsafe || upgrade) && !sameOrigin(req)) return respond(ctx, text(HttpResponseStatus.FORBIDDEN, "Forbidden"))
      when {
        path == "/ws" -> ws(ctx, req, decoder, auth)
        path == "/api/state" -> get(ctx, req) { json(RemoteProtocol.encode(api.state())) }
        path == "/api/launch" -> post(ctx, req) { launch(req) }
        path == "/api/resume" -> post(ctx, req) { resume(req) }
        req.method() == HttpMethod.GET -> asset(path)?.let { respond(ctx, it) } ?: respond(ctx, text(HttpResponseStatus.NOT_FOUND, "Not found"))
        else -> respond(ctx, text(HttpResponseStatus.NOT_FOUND, "Not found"))
      }
    }

    private inline fun get(ctx: ChannelHandlerContext, req: FullHttpRequest, f: () -> FullHttpResponse) {
      if (req.method() != HttpMethod.GET) return respond(ctx, text(HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed"))
      respond(ctx, serverError { f() })
    }

    private inline fun post(ctx: ChannelHandlerContext, req: FullHttpRequest, f: () -> FullHttpResponse) {
      if (req.method() != HttpMethod.POST) return respond(ctx, text(HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed"))
      respond(ctx, serverError { f() })
    }

    private inline fun serverError(f: () -> FullHttpResponse): FullHttpResponse =
      try {
        f()
      } catch (e: Exception) {
        rethrowCancellation(e)
        LOG.debug("remote request failed", e)
        json("{\"error\":\"failed\"}", HttpResponseStatus.INTERNAL_SERVER_ERROR)
      }

    private fun rethrowCancellation(e: Exception) = dev.phantom.ai.core.rethrowIfCancellation(e)

    private fun launch(req: FullHttpRequest): FullHttpResponse {
      val body = RemoteProtocol.decodeLaunch(req.content().toString(Charsets.UTF_8)) ?: return bad()
      val kind = AgentKind.entries.firstOrNull { it.command == body.kind } ?: return bad()
      if (!api.isListedWorktree(body.worktree)) return bad()
      val tab = api.launch(body.worktree, kind) ?: return json("{\"error\":\"not started\"}", HttpResponseStatus.INTERNAL_SERVER_ERROR)
      return json(com.google.gson.JsonObject().apply { addProperty("tabId", tab) }.toString())
    }

    private fun resume(req: FullHttpRequest): FullHttpResponse {
      val body = RemoteProtocol.decodeResume(req.content().toString(Charsets.UTF_8)) ?: return bad()
      val tab = api.resume(body.tabId) ?: return bad()
      return json(com.google.gson.JsonObject().apply { addProperty("tabId", tab) }.toString())
    }

    private fun bad() = json("{\"error\":\"bad request\"}", HttpResponseStatus.BAD_REQUEST)

    private fun pair(ctx: ChannelHandlerContext, req: FullHttpRequest, decoder: QueryStringDecoder) {
      val given = decoder.parameters()["token"]?.firstOrNull()
      if (req.method() != HttpMethod.GET || given == null || !RemoteToken.matches(given, tokenHash())) {
        return respond(ctx, text(HttpResponseStatus.UNAUTHORIZED, UNAUTHORIZED_BODY))
      }
      val cookie = "phantom_ai_${rt.port}=$given; HttpOnly; SameSite=Strict; Path=/; Max-Age=31536000"
      // a redirect that starts at another site keeps that site for the cookie rules, so a Strict cookie would not go with it:
      // such a browser gets a page of ours that moves on by itself, from our own origin
      val crossSite = req.headers().get("Sec-Fetch-Site")?.equals("cross-site", ignoreCase = true) == true
      val resp = if (crossSite) {
        html("<!doctype html><meta charset=utf-8><meta http-equiv=refresh content=\"0;url=/\"><title>Phantom AI Integration</title><a href=\"/\">Continue</a>")
      } else {
        empty(HttpResponseStatus.SEE_OTHER).also { it.headers().set(HttpHeaderNames.LOCATION, "/") }
      }
      resp.headers().add(HttpHeaderNames.SET_COOKIE, cookie)
      respond(ctx, resp)
    }

    private fun ws(ctx: ChannelHandlerContext, req: FullHttpRequest, decoder: QueryStringDecoder, auth: Auth) {
      val tab = decoder.parameters()["tab"]?.firstOrNull()
      if (req.method() != HttpMethod.GET || tab == null || req.headers().get(HttpHeaderNames.UPGRADE)?.equals("websocket", true) != true) {
        return respond(ctx, text(HttpResponseStatus.BAD_REQUEST, "Bad request"))
      }
      val io = api.terminal(tab) ?: return respond(ctx, text(HttpResponseStatus.NOT_FOUND, "Not found"))
      val host = req.headers().get(HttpHeaderNames.HOST) ?: return respond(ctx, text(HttpResponseStatus.BAD_REQUEST, "Bad request"))
      val factory = WebSocketServerHandshakerFactory("ws://$host/ws", null, false, MAX_FRAME)
      val handshaker = factory.newHandshaker(req) ?: return respond(ctx, text(HttpResponseStatus.BAD_REQUEST, "Bad request"))
      val session = WsSession(ctx.channel(), tab, io, auth.credential, rt)
      handshaker.handshake(ctx.channel(), req).addListener { f ->
        if (f.isSuccess) session.open() else ctx.close()
      }
    }

    private fun frame(ctx: ChannelHandlerContext, f: WebSocketFrame) {
      val s = sessions[ctx.channel()] ?: return
      if (!RemoteToken.matches(s.credential, tokenHash())) {
        s.close(4401, "unauthorized")
        return
      }
      when (f) {
        is CloseWebSocketFrame -> { s.detach(); ctx.close() }
        is TextWebSocketFrame -> {
          when (val m = RemoteProtocol.decodeClient(f.text())) {
            is ClientMessage.Auth -> {
              if (m.token.isNotEmpty() && !RemoteToken.matches(m.token, tokenHash())) {
                s.close(4401, "unauthorized")
              } else if (!s.authed) {
                s.authed = true
                s.frame()
              }
            }
            is ClientMessage.Input -> if (s.authed) s.io.send(m.data)
            null -> s.close(1003, "bad message")
          }
        }
        else -> {}
      }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
      sessions[ctx.channel()]?.detach()
      super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
      sessions[ctx.channel()]?.detach()
      ctx.close()
    }
  }

  private fun sameOrigin(req: HttpRequest): Boolean {
    val origin = req.headers().get(HttpHeaderNames.ORIGIN) ?: return false
    val host = req.headers().get(HttpHeaderNames.HOST) ?: return false
    return origin == "http://$host"
  }

  // ===== responses =====

  private fun respond(ctx: ChannelHandlerContext, r: FullHttpResponse) {
    r.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store")
    r.headers().set("X-Content-Type-Options", "nosniff")
    r.headers().set("Referrer-Policy", "no-referrer")
    r.headers().set(HttpHeaderNames.CONTENT_LENGTH, r.content().readableBytes())
    r.headers().set(HttpHeaderNames.CONNECTION, "close")
    ctx.writeAndFlush(r).addListener(ChannelFutureListener.CLOSE)
  }

  private fun response(status: HttpResponseStatus, type: String, body: ByteArray): FullHttpResponse =
    DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body)).also {
      it.headers().set(HttpHeaderNames.CONTENT_TYPE, type)
    }

  private fun empty(status: HttpResponseStatus) = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER)
  private fun text(status: HttpResponseStatus, body: String) = response(status, "text/plain; charset=utf-8", body.toByteArray())
  private fun html(body: String) = response(HttpResponseStatus.OK, "text/html; charset=utf-8", body.toByteArray())
  private fun json(body: String, status: HttpResponseStatus = HttpResponseStatus.OK) =
    response(status, "application/json; charset=utf-8", body.toByteArray())

  /** Only files of the fixed whitelist: no path is ever joined. */
  private fun asset(path: String): FullHttpResponse? {
    val (resource, type) = ASSETS[path] ?: return null
    val bytes = bytesOf(resource) ?: return null
    return response(HttpResponseStatus.OK, type, bytes)
  }

  private fun bytesOf(resource: String): ByteArray? = cache.getOrPut(resource) {
    RemoteControlServer::class.java.getResourceAsStream("/remote-web/$resource")?.use { it.readAllBytes() } ?: return null
  }

  private val cache = ConcurrentHashMap<String, ByteArray>()

  companion object {
    private val LOG = logger<RemoteControlServer>()
    const val UNAUTHORIZED_BODY = "Pair again from Phantom: Settings | Tools | Phantom AI Integration | Remote Control."
    private const val MAX_BODY = 1024 * 1024
    private const val MAX_FRAME = 64 * 1024
    private const val AUTH_MS = 5_000L
    private const val THROTTLE_MS = 50L
    private const val SWEEP_MS = 250L
    private val AUTH = AttributeKey.valueOf<Auth>("phantom.ai.auth")

    private val ASSETS = mapOf(
      "/" to ("index.html" to "text/html; charset=utf-8"),
      "/app.js" to ("app.js" to "text/javascript; charset=utf-8"),
      "/app.css" to ("app.css" to "text/css; charset=utf-8"),
      "/vendor/xterm/xterm.js" to ("vendor/xterm/xterm.js" to "text/javascript; charset=utf-8"),
      "/vendor/xterm/xterm.css" to ("vendor/xterm/xterm.css" to "text/css; charset=utf-8"),
      "/vendor/xterm/addon-fit.js" to ("vendor/xterm/addon-fit.js" to "text/javascript; charset=utf-8"),
    )

    private val threads = ThreadFactory { r -> Thread(r, "Phantom AI remote").apply { isDaemon = true } }

    fun getInstance(): RemoteControlServer = ApplicationManager.getApplication().getService(RemoteControlServer::class.java)
  }
}

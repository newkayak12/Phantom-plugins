// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.drivers.DbDriverDefinition

/** A host and port the tunnel forwards to. */
data class DbHostPort(val host: String, val port: Int)

/** An open SSH tunnel: connections to `127.0.0.1:`[localPort] reach the database through the SSH server. */
interface DbTunnel : AutoCloseable {
  val localPort: Int
  fun isAlive(): Boolean
  override fun close()
}

/** Opens a tunnel for [dataSource] to [target]. [secret]: the SSH password or key passphrase, if any. Blocking. */
fun interface DbTunnelOpener {
  fun open(dataSource: DbDataSource, secret: String?, target: DbHostPort): DbTunnel
}

/**
 * Where the database is, read from the JDBC URL, and the URL rewritten to reach it through a tunnel. The address is the
 * one after `//` (after `@` for Oracle's `@host:port`), for any driver; the explicit remote host and port of the data
 * source win, and are needed when the URL has no such address.
 */
object DbTunnelUrls {
  /** Where the host and port are in [url]: the span of `host[:port]` and the values; `null` when there is no single address. */
  internal data class Address(val start: Int, val end: Int, val host: String, val port: Int?)

  /** Where the address part starts: after Oracle's `@` (and a `//` after it), else after the first `//`; -1 when there is none. */
  private fun addressStart(url: String): Int {
    val at = url.indexOf('@')
    if (url.startsWith("jdbc:oracle:", ignoreCase = true) && at >= 0) return if (url.startsWith("//", at + 1)) at + 3 else at + 1
    val slashes = url.indexOf("//")
    return if (slashes >= 0) slashes + 2 else -1
  }

  /**
   * Several hosts (failover lists `h1:5432,h2:5432`) or an address list (`(DESCRIPTION=(ADDRESS=…))`): the driver could
   * reach any of them, so no single rewrite keeps it inside the tunnel.
   */
  internal fun multiHost(url: String): Boolean {
    if (Regex("""\(\s*(DESCRIPTION|ADDRESS)\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(url)) return true
    val start = addressStart(url)
    if (start < 0) return false
    var end = start
    while (end < url.length && url[end] !in "/;?#") end++
    val authority = url.substring(start, end)
    return authority.contains(',') || authority.startsWith("(")
  }

  internal fun address(url: String): Address? {
    val start = addressStart(url)
    if (start < 0 || multiHost(url)) return null
    var end = start
    while (end < url.length && url[end] !in "/;?#") {
      if (url[end] == '[') {
        end = url.indexOf(']', end).takeIf { it > 0 } ?: return null
      }
      end++
    }
    val authority = url.substring(start, end).substringAfterLast('@')
    val authorityStart = end - authority.length
    if (authority.isEmpty()) return null
    val host: String
    val portText: String?
    if (authority.startsWith("[")) {
      host = authority.substringBefore(']') + "]"
      portText = authority.substringAfter("]", "").removePrefix(":").takeIf { it.isNotEmpty() }
    }
    else {
      host = authority.substringBefore(':')
      // Oracle's host:port:SID
      portText = authority.substringAfter(':', "").substringBefore(':').takeIf { it.isNotEmpty() }
    }
    if (host.isEmpty() || host.contains('=')) return null
    // SQL Server's host\instance finds its port through the SQL Browser (UDP), which a tunnel does not carry
    if (host.contains('\\')) throw DbException(PhantomDbBundle.message("db.ssh.named.instance", host))
    val port = portText?.let { it.toIntOrNull() ?: return null }
    val spanEnd = if (portText != null) authorityStart + host.length + 1 + portText.length else authorityStart + host.length
    return Address(authorityStart, spanEnd, host.removePrefix("[").removeSuffix("]"), port)
  }

  /** Where the tunnel leads: the explicit remote host and port, else those of the URL (port: the driver's default). */
  fun target(url: String, definition: DbDriverDefinition?, dataSource: DbDataSource): DbHostPort {
    if (multiHost(url)) throw DbException(PhantomDbBundle.message("db.ssh.multi.host", url))
    val parsed = address(url)
    val host = dataSource.sshRemoteHost.trim().ifEmpty { parsed?.host }
    val port = dataSource.sshRemotePort ?: parsed?.port ?: definition?.defaultPort
    if (host.isNullOrEmpty() || port == null) throw DbException(PhantomDbBundle.message("db.ssh.no.address", url))
    return DbHostPort(host, port)
  }

  /**
   * [url] with its address replaced by `127.0.0.1:`[localPort]. A URL whose address cannot be read must name the explicit
   * remote `host:port` literally; anything else (several hosts included) is an error rather than a connection that
   * bypasses the tunnel.
   */
  fun rewrite(url: String, target: DbHostPort, localPort: Int): String {
    if (multiHost(url)) throw DbException(PhantomDbBundle.message("db.ssh.multi.host", url))
    val local = "127.0.0.1:$localPort"
    address(url)?.let { return url.substring(0, it.start) + local + url.substring(it.end) }
    val literal = "${target.host}:${target.port}"
    val i = url.indexOf(literal)
    if (i < 0 || url.indexOf(literal, i + 1) >= 0) throw DbException(PhantomDbBundle.message("db.ssh.no.address", url))
    return url.substring(0, i) + local + url.substring(i + literal.length)
  }
}

/** A use of a shared tunnel; [release] once the connection through it is closed. */
class DbTunnelLease internal constructor(val tunnel: DbTunnel, private val onRelease: () -> Unit) {
  private val released = java.util.concurrent.atomic.AtomicBoolean()

  fun release() {
    if (released.compareAndSet(false, true)) onRelease()
  }
}

/**
 * One tunnel per data source, shared by its connections, counted: it closes when the last connection through it is
 * released, on disconnect (once its connections are closed) and on dispose. A tunnel that died (network, server restart)
 * is opened again for the next connection. Opening (connect, password safe, host key prompt) happens outside the lock;
 * a second connection asking meanwhile waits for the same tunnel.
 */
class DbTunnels(@Volatile internal var opener: DbTunnelOpener) {
  private class Entry(val key: String) {
    val future = java.util.concurrent.CompletableFuture<DbTunnel>()
    var users = 0
    /** No longer handed out (disconnect, replaced): closes when its last user goes. */
    var detached = false
  }

  private val lock = Any()
  private val open = HashMap<String, Entry>()

  /** A lease on the data source's tunnel to [target], opening it (blocking, never under the lock) when needed. */
  fun acquire(dataSource: DbDataSource, target: DbHostPort, secret: () -> String?): DbTunnelLease {
    val key = key(dataSource, target)
    var stale: DbTunnel? = null
    val (entry, opens) = synchronized(lock) {
      val current = open[dataSource.id]
      val usable = current != null && current.key == key &&
                   (!current.future.isDone || current.future.getNow(null)?.isAlive() == true)
      if (usable) {
        current!!.users++
        current to false
      }
      else {
        if (current != null) {
          open.remove(dataSource.id)
          current.detached = true
          if (current.users == 0) stale = current.future.getNow(null)
        }
        Entry(key).also { it.users = 1; open[dataSource.id] = it } to true
      }
    }
    stale?.let { runCatching { it.close() } }
    val tunnel = if (opens) {
      try {
        opener.open(dataSource, secret(), target).also { entry.future.complete(it) }
      }
      catch (e: Throwable) {
        synchronized(lock) { if (open[dataSource.id] === entry) open.remove(dataSource.id) }
        entry.future.completeExceptionally(e)
        throw e
      }
    }
    else {
      try {
        entry.future.get()
      }
      catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
      }
    }
    return DbTunnelLease(tunnel) { release(dataSource.id, entry) }
  }

  private fun release(id: String, entry: Entry) {
    val close = synchronized(lock) {
      entry.users--
      if (entry.users > 0) return
      if (open[id] === entry) open.remove(id)
      entry.detached = true
      true
    }
    if (close) entry.future.getNow(null)?.let { runCatching { it.close() } }
  }

  /** A tunnel of its own, for Test Connection; the caller closes it. */
  fun throwaway(dataSource: DbDataSource, target: DbHostPort, secret: String?): DbTunnel = opener.open(dataSource, secret, target)

  fun isOpen(dataSourceId: String): Boolean = synchronized(lock) { open[dataSourceId] != null }

  /** Stops handing out the data source's tunnel; it closes when its connections release it (now, if none). */
  fun detach(dataSourceId: String) {
    val idle = synchronized(lock) {
      val e = open.remove(dataSourceId) ?: return
      e.detached = true
      if (e.users == 0) e else null
    }
    idle?.future?.getNow(null)?.let { runCatching { it.close() } }
  }

  fun closeAll() {
    val all = synchronized(lock) { open.values.toList().also { open.clear() } }
    all.forEach { e -> e.future.getNow(null)?.let { runCatching { it.close() } } }
  }

  private fun key(ds: DbDataSource, target: DbHostPort): String =
    listOf(ds.sshHost.trim(), ds.sshPort, ds.sshUser.trim(), ds.sshAuth, ds.sshKeyFile.trim(), target.host, target.port).joinToString("\u0000")
}

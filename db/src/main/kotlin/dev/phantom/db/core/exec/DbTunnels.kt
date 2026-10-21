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

  internal fun address(url: String): Address? {
    val slashes = url.indexOf("//")
    val start = when {
      slashes >= 0 -> slashes + 2
      url.startsWith("jdbc:oracle:", ignoreCase = true) && url.indexOf('@') >= 0 -> url.indexOf('@') + 1
      else -> return null
    }
    var end = start
    while (end < url.length && url[end] !in "/;?#,(" ) {
      if (url[end] == '[') {
        end = url.indexOf(']', end).takeIf { it > 0 } ?: return null
      }
      end++
    }
    // several hosts (failover lists) or a TNS descriptor: no single address to replace
    if (end < url.length && url[end] in ",(") return null
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
    val port = portText?.let { it.toIntOrNull() ?: return null }
    val spanEnd = if (portText != null) authorityStart + host.length + 1 + portText.length else authorityStart + host.length
    return Address(authorityStart, spanEnd, host.removePrefix("[").removeSuffix("]"), port)
  }

  /** Where the tunnel leads: the explicit remote host and port, else those of the URL (port: the driver's default). */
  fun target(url: String, definition: DbDriverDefinition?, dataSource: DbDataSource): DbHostPort {
    val parsed = address(url)
    val host = dataSource.sshRemoteHost.trim().ifEmpty { parsed?.host }
    val port = dataSource.sshRemotePort ?: parsed?.port ?: definition?.defaultPort
    if (host.isNullOrEmpty() || port == null) throw DbException(PhantomDbBundle.message("db.ssh.no.address", url))
    return DbHostPort(host, port)
  }

  /**
   * [url] with its address replaced by `127.0.0.1:`[localPort]. A URL whose address cannot be read must name the explicit
   * remote `host:port` literally; anything else is an error rather than a connection that bypasses the tunnel.
   */
  fun rewrite(url: String, target: DbHostPort, localPort: Int): String {
    val local = "127.0.0.1:$localPort"
    address(url)?.let { return url.substring(0, it.start) + local + url.substring(it.end) }
    val literal = "${target.host}:${target.port}"
    val i = url.indexOf(literal)
    if (i < 0) throw DbException(PhantomDbBundle.message("db.ssh.no.address", url))
    return url.substring(0, i) + local + url.substring(i + literal.length)
  }
}

/**
 * One tunnel per data source, shared by its connections; closed on disconnect, on dispose and when its connections are
 * all closed. A tunnel that died (network, server restart) is opened again on the next connection.
 */
class DbTunnels(@Volatile internal var opener: DbTunnelOpener) {
  private class Entry(val key: String, val tunnel: DbTunnel)

  private val open = HashMap<String, Entry>()

  /** The data source's tunnel to [target], opening it (blocking) when there is none, it died or the settings changed. */
  fun acquire(dataSource: DbDataSource, target: DbHostPort, secret: () -> String?): DbTunnel = synchronized(this) {
    val key = key(dataSource, target)
    open[dataSource.id]?.let { e ->
      if (e.key == key && e.tunnel.isAlive()) return e.tunnel
      open.remove(dataSource.id)
      runCatching { e.tunnel.close() }
    }
    val tunnel = opener.open(dataSource, secret(), target)
    open[dataSource.id] = Entry(key, tunnel)
    tunnel
  }

  /** A tunnel of its own, for Test Connection; the caller closes it. */
  fun throwaway(dataSource: DbDataSource, target: DbHostPort, secret: String?): DbTunnel = opener.open(dataSource, secret, target)

  fun isOpen(dataSourceId: String): Boolean = synchronized(this) { open[dataSourceId] != null }

  fun close(dataSourceId: String) {
    runCatching { detach(dataSourceId)?.close() }
  }

  /** Forgets the data source's tunnel without closing it: the caller closes it once its connections are closed. */
  fun detach(dataSourceId: String): DbTunnel? = synchronized(this) { open.remove(dataSourceId) }?.tunnel

  fun closeAll() {
    val all = synchronized(this) { open.values.toList().also { open.clear() } }
    all.forEach { runCatching { it.tunnel.close() } }
  }

  private fun key(ds: DbDataSource, target: DbHostPort): String =
    listOf(ds.sshHost.trim(), ds.sshPort, ds.sshUser.trim(), ds.sshAuth, ds.sshKeyFile.trim(), target.host, target.port).joinToString("\u0000")
}

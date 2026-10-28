// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.remote

data class Pairing(val host: String, val port: Int, val token: String)

/** http://host:port/pair?token=... ; the /pair page moves the token into the page's own storage and redirects. */
object PairingLink {
  private val LINK = Regex("^http://(\\[[0-9a-fA-F:.]+\\]|[A-Za-z0-9._-]+):(\\d{1,5})/pair\\?token=([A-Za-z0-9_-]+)$")

  fun build(address: String, port: Int, token: String): String {
    val host = if (':' in address && !address.startsWith("[")) "[$address]" else address
    return "http://$host:$port/pair?token=$token"
  }

  fun parse(link: String): Pairing? {
    val m = LINK.matchEntire(link.trim()) ?: return null
    val host = m.groupValues[1].removePrefix("[").removeSuffix("]")
    val port = m.groupValues[2].toIntOrNull() ?: return null
    if (!RemoteAddress.isConnectable(host, port)) return null
    return Pairing(host, port, m.groupValues[3])
  }
}

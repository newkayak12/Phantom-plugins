// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.remote

/** Where a host address points; ported from Orca's classifyRemotePairingHostname. */
enum class EndpointKind { LOOPBACK, TAILSCALE, LAN, PUBLIC, CUSTOM }

/** One network interface address; [hasDefaultRoute] is null when unknown. */
data class NetIface(val name: String, val address: String, val hasDefaultRoute: Boolean? = null)

/** An address the user can pick for pairing. */
data class AddressOption(val address: String, val label: String)

object RemoteAddress {
  const val LOOPBACK_ADDRESS = "127.0.0.1"

  private val LOOPBACK_HOSTS = setOf(
    "localhost", "localhost.localdomain", "localhost6", "localhost6.localdomain6", "ip6-localhost", "ip6-loopback", "127.0.0.1", "::1",
  )
  private val EMBEDDED_HEX = Regex("^::(?:ffff:)?([0-9a-f]{1,4}):([0-9a-f]{1,4})$", RegexOption.IGNORE_CASE)
  private val EMBEDDED_DOTTED = Regex("^::ffff:(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})$", RegexOption.IGNORE_CASE)
  private val BRIDGE = Regex("^(?:docker|br-|virbr|vmnet|vboxnet|veth|lxcbr|cni|flannel|cali|bridge)|VMware Network Adapter|VirtualBox Host-Only", RegexOption.IGNORE_CASE)
  private val HYPER_V = Regex("^vEthernet ", RegexOption.IGNORE_CASE)
  private val HYPER_V_LOCAL = Regex("^vEthernet \\((?:Default Switch|WSL(?: \\(Hyper-V firewall\\))?)\\)$", RegexOption.IGNORE_CASE)

  private fun normalize(host: String): String = host.trim().lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")

  private fun embeddedV4(h: String): String? {
    EMBEDDED_DOTTED.matchEntire(h)?.let { return it.groupValues[1] }
    val m = EMBEDDED_HEX.matchEntire(h) ?: return null
    val high = m.groupValues[1].toInt(16)
    val low = m.groupValues[2].toInt(16)
    return "${high shr 8}.${high and 0xff}.${low shr 8}.${low and 0xff}"
  }

  private fun octets(h: String): List<Int>? {
    val parts = h.split('.')
    if (parts.size != 4) return null
    val o = parts.map { p -> if (p.isNotEmpty() && p.all { it in '0'..'9' } && p.length <= 3) p.toInt() else return null }
    return if (o.any { it > 255 }) null else o
  }

  /** Tailscale: 100.64.0.0/10 and the IPv6 range fd7a:115c:a1e0::/48. */
  private fun isTailnet(h: String): Boolean {
    octets(h)?.let { return it[0] == 100 && it[1] in 64..127 }
    val parts = h.split(':')
    if (parts.size < 4) return false
    val head = parts.take(3).map { it.takeIf { p -> p.isNotEmpty() && p.length <= 4 }?.toIntOrNull(16) ?: return false }
    return head == listOf(0xfd7a, 0x115c, 0xa1e0)
  }

  private fun isPrivateV4(h: String): Boolean {
    val o = octets(h) ?: return false
    return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
  }

  private fun isPrivateV6(h: String): Boolean {
    val first = h.split(':')[0].toIntOrNull(16) ?: return false
    return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80
  }

  fun classify(host: String): EndpointKind {
    val n = normalize(host)
    embeddedV4(n)?.let { return classify(it) }
    if (n in LOOPBACK_HOSTS || n.endsWith(".localhost") || n.startsWith("127.")) return EndpointKind.LOOPBACK
    if (isTailnet(n)) return EndpointKind.TAILSCALE
    if (isPrivateV4(n) || isPrivateV6(n)) return EndpointKind.LAN
    return if ('.' in n || ':' in n) EndpointKind.PUBLIC else EndpointKind.CUSTOM
  }

  /** False for an unspecified address (0.0.0.0, ::, ::ffff:0.0.0.0), an empty host, or a port outside 1..65535. */
  fun isConnectable(host: String, port: Int): Boolean {
    if (port !in 1..65535) return false
    val n = normalize(host)
    if (n.isEmpty() || n == "0.0.0.0" || n == "::") return false
    return embeddedV4(n) != "0.0.0.0"
  }

  fun isVirtualBridge(name: String, hasDefaultRoute: Boolean? = null): Boolean {
    if (HYPER_V_LOCAL.containsMatchIn(name)) return true
    if (HYPER_V.containsMatchIn(name)) return hasDefaultRoute != true
    return BRIDGE.containsMatchIn(name)
  }

  /** The address to advertise: the first non-bridge tailnet one, else the first non-bridge one, else null. */
  fun autoSelect(ifaces: List<NetIface>): String? {
    val ok = ifaces.filter { !isVirtualBridge(it.name, it.hasDefaultRoute) }
    return (ok.firstOrNull { isTailnet(normalize(it.address)) } ?: ok.firstOrNull())?.address
  }

  fun label(iface: NetIface): String = when (classify(iface.address)) {
    EndpointKind.TAILSCALE -> "Tailscale · ${iface.address}"
    EndpointKind.LAN -> "Local network · ${iface.address}"
    EndpointKind.LOOPBACK -> "This computer · ${iface.address}"
    EndpointKind.PUBLIC, EndpointKind.CUSTOM -> "${iface.name} · ${iface.address}"
  }

  /** Loopback first, then the auto-selected address, then the rest; one entry per address. */
  fun options(ifaces: List<NetIface>): List<AddressOption> {
    val loop = NetIface("lo", LOOPBACK_ADDRESS)
    val auto = autoSelect(ifaces)
    val ordered = buildList {
      add(loop)
      auto?.let { a -> ifaces.firstOrNull { it.address == a }?.let(::add) }
      addAll(ifaces)
    }
    return ordered.distinctBy { it.address }.map { AddressOption(it.address, label(it)) }
  }
}

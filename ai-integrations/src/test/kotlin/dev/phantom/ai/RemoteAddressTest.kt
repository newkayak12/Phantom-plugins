// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.remote.EndpointKind
import dev.phantom.ai.remote.NetIface
import dev.phantom.ai.remote.RemoteAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAddressTest {
  private fun k(h: String) = RemoteAddress.classify(h)

  @Test
  fun `classifies hosts`() {
    assertEquals(EndpointKind.TAILSCALE, k("100.64.0.1"))
    assertEquals(EndpointKind.TAILSCALE, k("100.127.255.1"))
    assertEquals(EndpointKind.PUBLIC, k("100.128.0.1"))
    assertEquals(EndpointKind.TAILSCALE, k("fd7a:115c:a1e0::1"))
    assertEquals(EndpointKind.TAILSCALE, k("fd7a:115c:a1e0:ab12:4843:cd96:6258:1234"))
    assertEquals(EndpointKind.LAN, k("fd7a:115c:a1e1::1"))
    assertEquals("Tailscale · fd7a:115c:a1e0::1", RemoteAddress.label(NetIface("tailscale0", "fd7a:115c:a1e0::1")))
    assertEquals(EndpointKind.PUBLIC, k("172.15.0.1"))
    assertEquals(EndpointKind.LAN, k("172.16.0.1"))
    assertEquals(EndpointKind.LAN, k("192.168.1.5"))
    assertEquals(EndpointKind.LAN, k("10.0.0.2"))
    assertEquals(EndpointKind.LOOPBACK, k("::ffff:7f00:1"))
    assertEquals(EndpointKind.LOOPBACK, k("::ffff:127.0.0.1"))
    assertEquals(EndpointKind.LAN, k("fe80::1"))
    assertEquals(EndpointKind.LAN, k("fd00::1"))
    assertEquals(EndpointKind.LOOPBACK, k("[::1]"))
    assertEquals(EndpointKind.LOOPBACK, k("localhost."))
    assertEquals(EndpointKind.LOOPBACK, k("foo.localhost"))
    assertEquals(EndpointKind.LOOPBACK, k("127.5.5.5"))
    assertEquals(EndpointKind.CUSTOM, k("myhost"))
    assertEquals(EndpointKind.PUBLIC, k("example.com"))
    assertEquals(EndpointKind.PUBLIC, k("8.8.8.8"))
  }

  @Test
  fun `connectable`() {
    assertFalse(RemoteAddress.isConnectable("0.0.0.0", 47860))
    assertFalse(RemoteAddress.isConnectable("::", 47860))
    assertFalse(RemoteAddress.isConnectable("[::]", 47860))
    assertFalse(RemoteAddress.isConnectable("::ffff:0.0.0.0", 47860))
    assertFalse(RemoteAddress.isConnectable("::ffff:0:0", 47860))
    assertFalse(RemoteAddress.isConnectable("127.0.0.1", 0))
    assertFalse(RemoteAddress.isConnectable("127.0.0.1", 65536))
    assertTrue(RemoteAddress.isConnectable("127.0.0.1", 65535))
    assertTrue(RemoteAddress.isConnectable("[::1]", 1))
  }

  @Test
  fun `virtual bridges and auto select`() {
    assertTrue(RemoteAddress.isVirtualBridge("docker0"))
    assertTrue(RemoteAddress.isVirtualBridge("vEthernet (WSL)", true))
    assertTrue(RemoteAddress.isVirtualBridge("vEthernet (Ext)", null))
    assertFalse(RemoteAddress.isVirtualBridge("vEthernet (Ext)", true))
    assertFalse(RemoteAddress.isVirtualBridge("en0"))
    val ifaces = listOf(NetIface("docker0", "172.17.0.1"), NetIface("vEthernet (WSL)", "172.30.0.1"), NetIface("en0", "192.168.1.5"), NetIface("utun6", "100.100.1.1"))
    assertEquals("100.100.1.1", RemoteAddress.autoSelect(ifaces))
    assertEquals("192.168.1.5", RemoteAddress.autoSelect(ifaces.take(3)))
    assertNull(RemoteAddress.autoSelect(ifaces.take(2)))
  }

  @Test
  fun `labels and options`() {
    assertEquals("This computer · 127.0.0.1", RemoteAddress.label(NetIface("lo0", "127.0.0.1")))
    assertEquals("Local network · 192.168.1.5", RemoteAddress.label(NetIface("en0", "192.168.1.5")))
    assertEquals("Tailscale · 100.100.1.1", RemoteAddress.label(NetIface("utun6", "100.100.1.1")))
    assertEquals("eth0 · 8.8.8.8", RemoteAddress.label(NetIface("eth0", "8.8.8.8")))
    val o = RemoteAddress.options(listOf(NetIface("lo0", "127.0.0.1"), NetIface("en0", "192.168.1.5"), NetIface("docker0", "172.17.0.1"), NetIface("utun6", "100.100.1.1"), NetIface("en1", "192.168.1.5")))
    assertEquals(listOf("127.0.0.1", "100.100.1.1", "192.168.1.5", "172.17.0.1"), o.map { it.address })
    assertEquals("This computer · 127.0.0.1", o[0].label)
  }
}

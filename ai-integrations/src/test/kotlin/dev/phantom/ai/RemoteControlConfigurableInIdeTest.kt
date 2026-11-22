// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import dev.phantom.ai.remote.NetIface
import dev.phantom.ai.remote.RemoteAddress
import dev.phantom.ai.remote.RemoteControlConfigurable
import dev.phantom.ai.remote.RemoteControlServer
import dev.phantom.ai.remote.RemoteControlSettings
import dev.phantom.ai.remote.RemoteStatus
import dev.phantom.ai.remote.RemoteToken
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComboBox

class RemoteControlConfigurableInIdeTest : BasePlatformTestCase() {
  private fun all(c: Component): List<Component> = listOf(c) + ((c as? Container)?.components?.flatMap { all(it) } ?: emptyList())
  private inline fun <reified T : Component> find(root: Component, name: String) = all(root).filterIsInstance<T>().single { it.name == name }
  private fun make() = RemoteControlConfigurable(interfaces = { listOf(NetIface("en0", "192.168.1.5"), NetIface("utun3", "100.100.1.2")) })

  override fun tearDown() {
    try {
      RemoteControlServer.getInstance().stop()
      val s = RemoteControlSettings.getInstance().state
      s.enabled = false; s.address = "127.0.0.1"; s.port = 47860; s.tokenHash = null; s.allowUnencrypted = false
    } finally {
      super.tearDown()
    }
  }

  fun testTailscaleIpv6AddressOffered() {
    val labels = RemoteAddress.options(listOf(NetIface("en0", "192.168.1.5"), NetIface("utun4", "fd7a:115c:a1e0::1"))).map { it.label }
    assertTrue(labels.toString(), labels.contains("Tailscale · fd7a:115c:a1e0::1"))
  }

  fun testRegisteredUnderAccountsPage() {
    val ep = Configurable.APPLICATION_CONFIGURABLE.extensionList.single { it.instanceClass == RemoteControlConfigurable::class.java.name }
    assertEquals("dev.phantom.ai.accounts", ep.parentId)
    assertEquals("dev.phantom.ai.remote", ep.id)
    assertEquals("Remote Control", ep.displayName)
  }

  fun testCreatesHeadlessWithLabelledAddressesAndCustom() {
    val c = make()
    val ui = c.createComponent()
    val combo = find<JComboBox<*>>(ui, "remote.address")
    val labels = (0 until combo.itemCount).map { combo.getItemAt(it).toString() }
    assertEquals("This computer · 127.0.0.1", labels.first())
    assertTrue(labels.any { it == "Local network · 192.168.1.5" })
    assertTrue(labels.any { it == "Tailscale · 100.100.1.2" })
    assertEquals("Custom…", labels.last())
    assertFalse(c.isModified)
    c.disposeUIResources()
  }

  fun testApplySavesSettingsAndStartsServerOnLoopbackPortZero() {
    val s = RemoteControlSettings.getInstance().state
    s.tokenHash = RemoteToken.hash("seed")
    val c = make()
    val ui = c.createComponent()
    find<JBCheckBox>(ui, "remote.enable").isSelected = true
    find<JBTextField>(ui, "remote.port").text = "0"
    assertTrue(c.isModified)
    c.apply()
    assertTrue(s.enabled); assertEquals(0, s.port); assertEquals("127.0.0.1", s.address)
    assertTrue(RemoteControlServer.getInstance().status() is RemoteStatus.Running)
    assertFalse(c.isModified)
    find<JBCheckBox>(ui, "remote.enable").isSelected = false
    c.apply()
    assertEquals(RemoteStatus.Stopped, RemoteControlServer.getInstance().status())
    c.disposeUIResources()
  }

  fun testResetRestoresStoredValues() {
    val c = make()
    val ui = c.createComponent()
    find<JBTextField>(ui, "remote.port").text = "1234"
    assertTrue(c.isModified)
    c.reset()
    assertFalse(c.isModified)
    assertEquals("47860", find<JBTextField>(ui, "remote.port").text)
    c.disposeUIResources()
  }

  fun testGenerateLinkStoresOnlyTheHashAndShowsLinkAndQr() {
    val s = RemoteControlSettings.getInstance().state
    val c = make()
    val ui = c.createComponent()
    find<JBTextField>(ui, "remote.port").text = "0"
    find<JButton>(ui, "remote.generate").doClick()
    val link = find<JBTextField>(ui, "remote.link")
    assertFalse(link.isEditable)
    assertTrue(link.text.startsWith("http://127.0.0.1:0/pair?token="))
    val token = link.text.substringAfter("token=")
    assertEquals(RemoteToken.hash(token), s.tokenHash)
    assertFalse(s.tokenHash!!.contains(token))
    assertNotNull(find<JBLabel>(ui, "remote.qr").icon)
    find<JButton>(ui, "remote.generate").doClick()
    assertNotSame(token, link.text.substringAfter("token="))
    c.disposeUIResources()
  }

  fun testWarningOnlyForNonLoopbackNonTailscale() {
    val c = make()
    val ui = c.createComponent()
    val combo = find<JComboBox<*>>(ui, "remote.address")
    val warn = find<JBLabel>(ui, "remote.warning")
    assertEquals(
      "Unencrypted on this network: anyone on it can read the pairing token and the terminal traffic, and the token lets them control your agents. Prefer Tailscale or this computer.",
      warn.text,
    )
    assertFalse(warn.isVisible)
    val notice = find<JBLabel>(ui, "remote.notice")
    assertTrue(notice.isVisible)
    assertTrue(notice.text, notice.text.contains("no TLS") && notice.text.contains("its token"))
    // off and on this computer only until the user picks another address
    assertFalse(dev.phantom.ai.remote.RemoteControlState().enabled)
    assertEquals("127.0.0.1", dev.phantom.ai.remote.RemoteControlState().address)
    for ((addr, expected) in listOf("192.168.1.5" to true, "100.100.1.2" to false)) {
      combo.selectedIndex = (0 until combo.itemCount).first { combo.getItemAt(it).toString().endsWith(addr) }
      assertEquals(addr, expected, warn.isVisible)
    }
    c.disposeUIResources()
  }

  fun testLanAddressNeedsExplicitOptIn() {
    val s = RemoteControlSettings.getInstance().state
    s.tokenHash = RemoteToken.hash("seed")
    val c = make()
    val ui = c.createComponent()
    val combo = find<JComboBox<*>>(ui, "remote.address")
    val optIn = find<JBCheckBox>(ui, "remote.allowUnencrypted")
    find<JBCheckBox>(ui, "remote.enable").isSelected = true
    find<JBTextField>(ui, "remote.port").text = "0"
    combo.selectedIndex = (0 until combo.itemCount).first { combo.getItemAt(it).toString().endsWith("192.168.1.5") }
    assertTrue(optIn.isVisible)
    assertThrows(com.intellij.openapi.options.ConfigurationException::class.java) { c.apply() }
    assertFalse(s.enabled)
    // Tailscale is encrypted: no opt-in needed, and the box is hidden
    combo.selectedIndex = (0 until combo.itemCount).first { combo.getItemAt(it).toString().endsWith("100.100.1.2") }
    assertFalse(optIn.isVisible)
    c.disposeUIResources()
  }

  fun testServerRefusesUnencryptedAddressWithoutOptIn() {
    val s = RemoteControlSettings.getInstance().state
    s.tokenHash = RemoteToken.hash("seed")
    s.enabled = true; s.address = "192.168.1.5"; s.port = 0; s.allowUnencrypted = false
    RemoteControlServer.getInstance().applySettings()
    val st = RemoteControlServer.getInstance().status()
    assertTrue(st.toString(), st is RemoteStatus.Failed && st.message.contains("192.168.1.5"))
    assertTrue(RemoteAddress.isProtected("127.0.0.1") && RemoteAddress.isProtected("100.100.1.2"))
    assertFalse(RemoteAddress.isProtected("192.168.1.5"))
  }

  fun testHintShownWhenHashExistsButNoRawToken() {
    RemoteControlSettings.getInstance().state.tokenHash = RemoteToken.hash("old")
    val c = make()
    val ui = c.createComponent()
    val hint = find<JBLabel>(ui, "remote.hint")
    assertTrue(hint.isVisible)
    assertEquals("Generate a new link to pair another device (paired browsers keep working)", hint.text)
    find<JButton>(ui, "remote.generate").doClick()
    assertFalse(hint.isVisible)
    c.disposeUIResources()
  }
}

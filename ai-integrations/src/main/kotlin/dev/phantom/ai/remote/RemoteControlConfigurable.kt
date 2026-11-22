// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.remote

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.phantom.ai.core.PhantomAiBundle
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.DateFormat
import java.util.Date
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Settings | Tools | Phantom AI Integration | Remote Control. The raw pairing token lives only in this page's memory; settings
 * keep its hash. Generate link rotates the token (the old link and its sessions stop working); browsers already paired keep
 * their cookie only until the next rotation, as the server checks the current hash on every request.
 */
class RemoteControlConfigurable(
  private val settings: RemoteControlSettings = RemoteControlSettings.getInstance(),
  private val server: RemoteControlServer = RemoteControlServer.getInstance(),
  private val interfaces: () -> List<NetIface> = ::systemInterfaces,
) : Configurable {
  private class Choice(val address: String?, val label: String) {
    override fun toString() = label
  }

  private var root: JPanel? = null
  private var enable: JBCheckBox? = null
  private var address: JComboBox<Choice>? = null
  private var allowUnencrypted: JBCheckBox? = null
  private var custom: JBTextField? = null
  private var port: JBTextField? = null
  private var link: JBTextField? = null
  private var qr: JBLabel? = null
  private var statusLine: JBLabel? = null
  private var warning: JBLabel? = null
  private var hint: JBLabel? = null
  private val clientsModel = DefaultListModel<String>()
  private var timer: Timer? = null

  /** The raw token of the link on screen; never persisted, gone with the page. */
  private var rawToken: String? = null

  override fun getDisplayName(): String = PhantomAiBundle.message("remote.title")

  override fun createComponent(): JComponent {
    val customLabel = PhantomAiBundle.message("remote.address.custom")
    val choices = RemoteAddress.options(interfaces()).map { Choice(it.address, it.label) } + Choice(null, customLabel)
    val e = JBCheckBox(PhantomAiBundle.message("remote.enable")).apply { name = "remote.enable" }
    val a = JComboBox(choices.toTypedArray()).apply { name = "remote.address" }
    val c = JBTextField(16).apply { name = "remote.custom" }
    val p = JBTextField(6).apply { name = "remote.port" }
    val l = JBTextField(40).apply { name = "remote.link"; isEditable = false }
    val q = JBLabel().apply { name = "remote.qr" }
    val s = JBLabel().apply { name = "remote.status" }
    val w = JBLabel(PhantomAiBundle.message("remote.warning")).apply { name = "remote.warning"; isVisible = false; foreground = JBColor.RED }
    // Always shown: the server has no TLS, so what it carries is readable on any network it is reachable from.
    val n = JBLabel("<html><body style='width: 460px'>${PhantomAiBundle.message("remote.notice")}</body></html>").apply { name = "remote.notice" }
    val h = JBLabel(PhantomAiBundle.message("remote.hint.regenerate")).apply { name = "remote.hint"; isVisible = false }
    val u = JBCheckBox(PhantomAiBundle.message("remote.unencrypted.allow")).apply { name = "remote.allowUnencrypted"; isVisible = false }
    allowUnencrypted = u
    enable = e; address = a; custom = c; port = p; link = l; qr = q; statusLine = s; warning = w; hint = h

    a.addActionListener { c.isEnabled = (a.selectedItem as? Choice)?.address == null; refreshWarning() }
    c.document.addDocumentListener(object : javax.swing.event.DocumentListener {
      override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = refreshWarning()
      override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = refreshWarning()
      override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = refreshWarning()
    })

    val generate = JButton(PhantomAiBundle.message("remote.generate")).apply { name = "remote.generate"; addActionListener { generateLink() } }
    val copy = JButton(PhantomAiBundle.message("remote.copy")).apply {
      name = "remote.copy"
      addActionListener {
        link?.text?.takeIf { it.isNotEmpty() }?.let { t ->
          com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(StringSelection(t))
        }
      }
    }
    val clients = JBList(clientsModel).apply { name = "remote.clients" }

    fun row(vararg cs: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { cs.forEach { add(it) } }
    val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(4) }
    panel.add(row(e))
    panel.add(row(n))
    panel.add(row(JBLabel(PhantomAiBundle.message("remote.address")), a, c, JBLabel(PhantomAiBundle.message("remote.port")), p))
    panel.add(row(generate))
    panel.add(row(JBLabel(PhantomAiBundle.message("remote.link")), l, copy))
    panel.add(row(q))
    panel.add(row(s))
    panel.add(row(w))
    panel.add(row(u))
    panel.add(row(h))
    panel.add(row(JBLabel(PhantomAiBundle.message("remote.clients"))))
    panel.add(clients)
    root = panel
    reset()
    timer = Timer(2000) { refreshStatus() }.apply { start() }
    return panel
  }

  private fun chosenAddress(): String {
    val ch = address?.selectedItem as? Choice
    return ch?.address ?: custom?.text?.trim().orEmpty()
  }

  private fun refreshWarning() {
    val kind = RemoteAddress.classify(chosenAddress())
    val exposed = chosenAddress().isNotEmpty() && kind != EndpointKind.LOOPBACK && kind != EndpointKind.TAILSCALE
    warning?.isVisible = exposed
    allowUnencrypted?.isVisible = exposed
  }

  private fun refreshStatus() {
    statusLine?.text = when (val st = server.status()) {
      is RemoteStatus.Running -> PhantomAiBundle.message("remote.status.running", st.url)
      is RemoteStatus.Failed -> PhantomAiBundle.message("remote.status.failed", st.message)
      RemoteStatus.Stopped -> PhantomAiBundle.message("remote.status.off")
    }
    clientsModel.clear()
    val list = server.clients()
    if (list.isEmpty()) clientsModel.addElement(PhantomAiBundle.message("remote.clients.none"))
    list.forEach {
      clientsModel.addElement(PhantomAiBundle.message("remote.client", it.remoteAddress, it.tabId,
        DateFormat.getTimeInstance().format(Date(it.connectedSince))))
    }
  }

  private fun portValue(): Int? = port?.text?.trim()?.toIntOrNull()?.takeIf { it in 0..65535 }

  private fun generateLink() {
    val host = chosenAddress()
    val portNo = portValue() ?: return
    if (host.isEmpty()) return
    val token = RemoteToken.generate()
    rawToken = token
    settings.state.tokenHash = RemoteToken.hash(token)
    if (settings.state.enabled) server.applySettings() // the server reads the new hash live and drops sessions of the old token
    val url = PairingLink.build(host, portNo, token)
    link?.text = url
    qr?.icon = ImageIcon(QrImage.render(url))
    hint?.isVisible = false
    refreshStatus()
  }

  override fun isModified(): Boolean {
    val s = settings.state
    return enable?.isSelected != s.enabled || chosenAddress() != (s.address ?: "127.0.0.1") || port?.text?.trim() != s.port.toString() ||
           allowUnencrypted?.isSelected != s.allowUnencrypted
  }

  @Throws(ConfigurationException::class)
  override fun apply() {
    val no = portValue() ?: throw ConfigurationException(PhantomAiBundle.message("remote.port.invalid"))
    val host = chosenAddress()
    if (host.isEmpty()) throw ConfigurationException(PhantomAiBundle.message("remote.address.invalid"))
    val optedIn = allowUnencrypted?.isSelected == true
    if (enable?.isSelected == true && !RemoteAddress.isProtected(host) && !optedIn) {
      throw ConfigurationException(PhantomAiBundle.message("remote.unencrypted.required", host))
    }
    val s = settings.state
    s.allowUnencrypted = optedIn
    s.enabled = enable?.isSelected == true
    s.address = host
    s.port = no
    server.applySettings()
    refreshStatus()
  }

  override fun reset() {
    val s = settings.state
    enable?.isSelected = s.enabled
    val a = address
    val wanted = s.address ?: "127.0.0.1"
    if (a != null) {
      val known = (0 until a.itemCount).map { a.getItemAt(it) }.firstOrNull { it.address == wanted }
      if (known != null) {
        a.selectedItem = known
        custom?.text = ""
      } else {
        a.selectedIndex = a.itemCount - 1
        custom?.text = wanted
      }
      custom?.isEnabled = (a.selectedItem as? Choice)?.address == null
    }
    port?.text = s.port.toString()
    allowUnencrypted?.isSelected = s.allowUnencrypted
    // a hash without a raw token means the IDE restarted since the link was made: the link cannot be shown again
    hint?.isVisible = s.tokenHash != null && rawToken == null
    refreshWarning()
    refreshStatus()
  }

  override fun disposeUIResources() {
    timer?.stop()
    timer = null
    rawToken = null
    allowUnencrypted = null
    root = null; enable = null; address = null; custom = null; port = null; link = null; qr = null
    statusLine = null; warning = null; hint = null
  }

  companion object {
    /** Seam: the IPv4 and non-link-local IPv6 addresses of the up interfaces; tests pass their own list. */
    fun systemInterfaces(): List<NetIface> = try {
      NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { ni ->
        ni.inetAddresses.toList().filter { it is Inet4Address || (it is java.net.Inet6Address && !it.isLinkLocalAddress) }
          .map { NetIface(ni.name, it.hostAddress.substringBefore('%')) }
      }
    } catch (e: java.net.SocketException) {
      emptyList()
    }
  }
}

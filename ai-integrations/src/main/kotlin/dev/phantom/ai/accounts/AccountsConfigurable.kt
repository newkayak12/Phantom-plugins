// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.accounts

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.JBUI
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.PhantomAiBundle
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings | Tools | Phantom AI Integration: per provider a System default row, one row per account, the active one selected. */
class AccountsConfigurable(private val service: AccountsService = AccountsService.getInstance()) : Configurable {
  private val chosen = mutableMapOf<AgentKind, String?>()
  private var root: JPanel? = null

  override fun getDisplayName(): String = PhantomAiBundle.message("accounts.title")

  override fun createComponent(): JComponent {
    AgentKind.entries.forEach { chosen[it] = service.active(it)?.id }
    val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    root = panel
    rebuild()
    return panel
  }

  private fun rebuild() {
    val panel = root ?: return
    panel.removeAll()
    for (kind in AgentKind.entries) {
      panel.add(JBLabel(kind.displayName).apply { border = JBUI.Borders.empty(8, 0, 2, 0) })
      val group = ButtonGroup()
      fun row(text: String, id: String?, account: AgentAccount?) {
        val signedIn = account == null || service.signedIn(account)
        val shown = if (signedIn) text else PhantomAiBundle.message("accounts.needs.sign.in", text)
        val radio = JBRadioButton(shown, chosen[kind] == id).apply { name = "${kind.name}:${id ?: "default"}"; isEnabled = signedIn }
        radio.addActionListener { chosen[kind] = id }
        group.add(radio)
        val line = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        line.add(radio)
        if (account != null) {
          if (!signedIn) {
            line.add(JButton(PhantomAiBundle.message("accounts.sign.in")).apply {
              name = "signin:${kind.name}:$id"
              addActionListener { signingIn { service.signInAgain(kind, account.id) } }
            })
          }
          line.add(JButton(PhantomAiBundle.message("accounts.remove")).apply {
            name = "remove:${kind.name}:$id"
            addActionListener {
              if (MessageDialogBuilder.yesNo(PhantomAiBundle.message("accounts.remove"), PhantomAiBundle.message("accounts.remove.confirm", account.label)).ask(panel)) {
                service.remove(kind, account.id)
                if (chosen[kind] == id) chosen[kind] = null
                rebuild()
              }
            }
          })
        }
        panel.add(line)
      }
      row(PhantomAiBundle.message("accounts.default"), null, null)
      service.accounts(kind).forEach { row(it.label, it.id, it) }
      panel.add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        add(JButton(PhantomAiBundle.message("accounts.add")).apply {
          name = "add:${kind.name}"
          addActionListener { signingIn { service.add(kind) } }
        })
      })
    }
    panel.revalidate()
    panel.repaint()
  }

  /** Says why a login failed; a cancel needs no words. */
  private fun signingIn(login: () -> Unit) {
    try {
      login()
    } catch (e: LoginFailed) {
      Messages.showErrorDialog(root, e.message, PhantomAiBundle.message("accounts.login.failed.title"))
    } catch (_: ProcessCanceledException) {
    }
    rebuild()
  }

  override fun isModified(): Boolean = AgentKind.entries.any { chosen[it] != service.active(it)?.id }

  override fun apply() {
    AgentKind.entries.forEach { service.setActive(it, chosen[it]) }
  }

  override fun reset() {
    AgentKind.entries.forEach { chosen[it] = service.active(it)?.id }
    rebuild()
  }

  override fun disposeUIResources() {
    root = null
  }
}

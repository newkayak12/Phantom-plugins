// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.accounts

import com.intellij.openapi.options.Configurable
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

/** Settings | Tools | AI Integrations: per provider a System default row, one row per account, the active one selected. */
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
      fun row(text: String, id: String?, removable: Boolean) {
        val radio = JBRadioButton(text, chosen[kind] == id).apply { name = "${kind.name}:${id ?: "default"}" }
        radio.addActionListener { chosen[kind] = id }
        group.add(radio)
        val line = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        line.add(radio)
        if (removable) {
          line.add(JButton(PhantomAiBundle.message("accounts.remove")).apply {
            name = "remove:${kind.name}:$id"
            addActionListener {
              service.remove(kind, id!!) // removable rows always have an id
              if (chosen[kind] == id) chosen[kind] = null
              rebuild()
            }
          })
        }
        panel.add(line)
      }
      row(PhantomAiBundle.message("accounts.default"), null, false)
      service.accounts(kind).forEach { row(it.label, it.id, true) }
      panel.add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        add(JButton(PhantomAiBundle.message("accounts.add")).apply {
          name = "add:${kind.name}"
          addActionListener { service.add(kind); rebuild() }
        })
      })
    }
    panel.revalidate()
    panel.repaint()
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

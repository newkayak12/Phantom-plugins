// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.PhantomAiBundle
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings | Tools | Phantom AI Integration | Terminal: where new agent terminals open. Open ones stay where they are. */
class TerminalConfigurable(private val backends: () -> List<TerminalBackend> = TerminalBackends::available) : Configurable {
  private class Choice(val id: String?, val label: String) {
    override fun toString() = label
  }

  private var combo: JComboBox<Choice>? = null

  override fun getDisplayName(): String = PhantomAiBundle.message("terminal.title")

  override fun createComponent(): JComponent {
    val auto = Choice(null, PhantomAiBundle.message("terminal.backend.auto"))
    val c = JComboBox((listOf(auto) + backends().map { Choice(it.id, it.displayName) }).toTypedArray()).apply { name = "terminal.backend" }
    combo = c
    val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(4) }
    panel.add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { add(JBLabel(PhantomAiBundle.message("terminal.backend"))); add(c) })
    panel.add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
      add(JBLabel("<html><body style='width: 460px'>${PhantomAiBundle.message("terminal.backend.hint")}</body></html>"))
    })
    reset()
    return panel
  }

  private fun selected(): String? = (combo?.selectedItem as? Choice)?.id

  override fun isModified() = selected() != AiState.getInstance().state.terminalBackend?.ifBlank { null }

  override fun apply() {
    AiState.getInstance().state.terminalBackend = selected()
  }

  override fun reset() {
    val c = combo ?: return
    val wanted = AiState.getInstance().state.terminalBackend?.ifBlank { null }
    c.selectedItem = (0 until c.itemCount).map { c.getItemAt(it) }.firstOrNull { it.id == wanted } ?: c.getItemAt(0)
  }

  override fun disposeUIResources() {
    combo = null
  }
}

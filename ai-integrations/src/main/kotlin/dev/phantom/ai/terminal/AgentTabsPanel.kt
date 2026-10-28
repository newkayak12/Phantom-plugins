// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.phantom.ai.core.PhantomAiBundle
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel

/** The tab strip (closable "<kind> · <branch>" tabs) and the selected terminal below it; a hint while there is none. */
class AgentTabsPanel(
  private val project: Project,
  parent: Disposable,
  private val host: AgentTabsHost = AgentTabsHost.getInstance(project),
) : JPanel(BorderLayout()) {
  private val strip = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { name = "agent.tabs.strip" }
  private val cards = CardLayout()
  private val body = JPanel(cards)
  private val placeholder = JBLabel(PhantomAiBundle.message("toolwindow.no.terminal")).apply {
    name = "agent.tabs.placeholder"
    horizontalAlignment = JBLabel.CENTER
    foreground = JBColor.GRAY
  }
  private val shown = HashMap<String, JComponent>()
  private var focusedId: String? = null

  init {
    strip.border = JBUI.Borders.empty(2, 4, 0, 4)
    body.add(placeholder, PLACEHOLDER)
    add(strip, BorderLayout.NORTH)
    add(body, BorderLayout.CENTER)
    project.messageBus.connect(parent).subscribe(AgentTabsHost.TOPIC, AgentTabsListener { rebuild() })
    rebuild()
  }

  @TestOnly fun tabTitles(): List<String> = host.tabs().map { it.title }

  @TestOnly fun showsPlaceholder(): Boolean = host.tabs().isEmpty() && placeholder.parent === body

  private fun rebuild() {
    val tabs = host.tabs()
    val selected = host.selected()
    (shown.keys - tabs.map { it.id }.toSet()).forEach { body.remove(shown.remove(it)) }
    tabs.filter { it.id !in shown }.forEach {
      body.add(it.component, it.id)
      shown[it.id] = it.component
    }
    strip.removeAll()
    tabs.forEach { strip.add(label(it, it.id == selected?.id)) }
    cards.show(body, selected?.id ?: PLACEHOLDER)
    if (selected != null && selected.id != focusedId) {
      selected.preferredFocus?.let { f -> com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({ IdeFocusManager.getInstance(project).requestFocus(f, true) }, project.disposed) }
    }
    focusedId = selected?.id
    strip.isVisible = tabs.isNotEmpty()
    revalidate()
    repaint()
  }

  private fun label(tab: AgentTab, selected: Boolean): JComponent {
    val title = JBLabel(tab.title, tab.icon, javax.swing.SwingConstants.LEFT).apply { name = "agent.tab.${tab.id}" }
    val close = JBLabel("×").apply {
      name = "agent.tab.close.${tab.id}"
      foreground = JBColor.GRAY
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) = host.close(tab.id)
      })
    }
    return JPanel(FlowLayout(FlowLayout.LEFT, 6, 3)).apply {
      isOpaque = selected
      add(title)
      add(close)
      border = if (selected) JBUI.Borders.customLine(JBColor(0x3574F0, 0x548AF7), 0, 0, 2, 0) else JBUI.Borders.customLine(JBColor.border(), 0, 0, 0, 0)
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      addMouseListener(object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
          host.select(tab.id)
        }
      })
      title.addMouseListener(object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
          host.select(tab.id)
        }
      })
    }
  }

  private companion object {
    const val PLACEHOLDER = "placeholder"
  }
}

// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.cpu

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import kotlin.math.roundToInt

private const val DUMP_THREADS_ACTION = "Performance.DumpThreads"

/** The hover popup: what the IDE spent CPU on, the busiest processes, and a thread dump link. Closes when the mouse leaves. */
internal class CpuPopup(private val owner: JComponent, initial: CpuSnapshot, private val onClosed: () -> Unit) {
  private val title = JBLabel().apply { font = JBFont.label().asBold() }
  private val measuring = JBLabel(PhantomCpuBundle.message("popup.measuring")).apply { foreground = UIUtil.getContextHelpForeground() }
  private val workNames = Array(Work.entries.size) { JBLabel() }
  private val workBars = Array(Work.entries.size) { Bar() }
  private val workPercents = Array(Work.entries.size) { JBLabel().apply { horizontalAlignment = JBLabel.RIGHT } }
  private val processNames = Array(3) { JBLabel() }
  private val processPercents = Array(3) { JBLabel().apply { horizontalAlignment = JBLabel.RIGHT } }
  private val self = JBLabel().apply { foreground = UIUtil.getContextHelpForeground(); font = JBFont.small() }
  private val popup: JBPopup

  init {
    val panel = JPanel(GridBagLayout()).apply { border = JBUI.Borders.empty(8, 10) }
    val c = GridBagConstraints().apply { gridx = 0; gridy = 0; anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL }
    fun row(component: JComponent, top: Int = 0) {
      c.gridx = 0; c.gridwidth = 3; c.insets = JBUI.insetsTop(top)
      panel.add(component, c)
      c.gridy++; c.gridwidth = 1
    }
    row(title)
    row(JBLabel(PhantomCpuBundle.message("popup.work")).apply { foreground = UIUtil.getContextHelpForeground() }, 6)
    row(measuring, 4)
    for (i in workNames.indices) {
      c.insets = JBUI.insetsTop(4)
      c.gridx = 0; panel.add(workNames[i], c)
      c.gridx = 1; c.insets = JBUI.insets(4, 10, 0, 6); panel.add(workBars[i], c)
      c.gridx = 2; c.insets = JBUI.insetsTop(4); panel.add(workPercents[i], c)
      c.gridy++
    }
    row(JSeparator(), 8)
    row(JBLabel(PhantomCpuBundle.message("popup.processes")).apply { foreground = UIUtil.getContextHelpForeground() }, 6)
    for (i in processNames.indices) {
      c.insets = JBUI.insetsTop(4)
      c.gridx = 0; c.gridwidth = 2; panel.add(processNames[i], c)
      c.gridx = 2; c.gridwidth = 1; panel.add(processPercents[i], c)
      c.gridy++
    }
    row(JSeparator(), 8)
    val dump = ActionManager.getInstance().getAction(DUMP_THREADS_ACTION)
    if (dump != null) {
      row(ActionLink(PhantomCpuBundle.message("popup.dump")) {
        close()
        ActionManager.getInstance().tryToExecute(dump, null, owner, ActionPlaces.STATUS_BAR_PLACE, true)
      }, 6)
    }
    row(self, 6)
    update(initial)

    popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, null)
      .setRequestFocus(false)
      .setFocusable(false)
      .setResizable(false)
      .setMovable(false)
      .setCancelOnClickOutside(true)
      .setCancelOnMouseOutCallback { e -> !overOwner(e) }
      .addListener(object : JBPopupListener {
        override fun onClosed(event: LightweightWindowEvent) = this@CpuPopup.onClosed()
      })
      .createPopup()
  }

  fun show() {
    val size = popup.content.preferredSize
    popup.show(RelativePoint(owner, Point(owner.width - size.width, -size.height)))
  }

  fun close() {
    popup.cancel()
  }

  fun update(s: CpuSnapshot) {
    title.text = PhantomCpuBundle.message("popup.title", s.ide)
    val breakdown = s.breakdown
    measuring.isVisible = breakdown == null
    val order = Work.entries.sortedByDescending { breakdown?.get(it.ordinal) ?: 0.0 }
    for (i in order.indices) {
      val share = breakdown?.get(order[i].ordinal) ?: 0.0
      workNames[i].text = order[i].displayName
      workBars[i].fraction = share
      workPercents[i].text = "${(share * 100).roundToInt()}%"
      workNames[i].isVisible = breakdown != null
      workBars[i].isVisible = breakdown != null
      workPercents[i].isVisible = breakdown != null
    }
    for (i in processNames.indices) {
      val p = s.topProcesses.getOrNull(i)
      processNames[i].text = p?.name ?: " "
      processPercents[i].text = if (p == null) " " else "${p.cpu.roundToInt()}%"
    }
    self.text = PhantomCpuBundle.message("popup.self", "%.2f".format(s.self))
  }

  private fun overOwner(e: MouseEvent): Boolean {
    if (!owner.isShowing) return false
    val bounds = Rectangle(owner.locationOnScreen, owner.size)
    bounds.grow(0, JBUI.scale(4))
    val p = e.locationOnScreen
    return bounds.contains(p)
  }

  /** A share bar; fixed size so the popup does not jump while values change. */
  private class Bar : JComponent() {
    var fraction = 0.0
      set(value) {
        if (field != value) { field = value; repaint() }
      }

    override fun getPreferredSize() = Dimension(JBUI.scale(120), JBUI.scale(8))
    override fun getMinimumSize() = preferredSize

    override fun paintComponent(g: Graphics) {
      val h = JBUI.scale(6)
      val y = (height - h) / 2
      g.color = TRACK
      g.fillRect(0, y, width, h)
      g.color = FILL
      g.fillRect(0, y, (width * fraction).roundToInt(), h)
    }
  }

  private companion object {
    val TRACK: Color = JBColor(0xEBECF0, 0x393B40)
    val FILL: Color = JBColor(0x3574F0, 0x548AF7)
  }
}

// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.cpu

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.Path2D
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

class CpuWidgetFactory : StatusBarWidgetFactory {
  override fun getId(): String = ID
  override fun getDisplayName(): String = PhantomCpuBundle.message("widget.name")
  override fun isAvailable(project: Project): Boolean = true
  override fun isEnabledByDefault(): Boolean = true
  override fun createWidget(project: Project): StatusBarWidget = CpuWidget()

  companion object {
    const val ID = "PhantomCpuIndicator"
  }
}

private class CpuWidget : CustomStatusBarWidget {
  private val sampler = service<CpuSampler>()
  private val component = CpuComponent(sampler)

  override fun ID(): String = CpuWidgetFactory.ID
  override fun getComponent(): JComponent = component

  override fun install(statusBar: StatusBar) {
    sampler.addListener(component, this)
  }

  override fun dispose() {
    component.closePopup()
  }
}

/**
 * The status bar graph and numbers.
 *
 * Painting is kept cheap because the widget it replaces stalled the EDT in its paint: no offscreen image (the graph is
 * two [Path2D] lines drawn straight onto the status bar), no font, color, stroke or path created per paint (all are
 * fields, rebuilt only in [updateUI]), and a repaint of this component only, only when the sampler reports a change
 * and the window is active and not minimized. A change missed while the window was inactive is painted on activation.
 */
internal class CpuComponent(private val sampler: CpuSampler) : JComponent(), CpuSampler.Listener {
  private var textFont: Font = JBUI.Fonts.label()
  private var graphWidth = 0
  private var gap = 0
  private var pad = 0
  private var arc = 0
  private var ideTextWidth = 0
  private var systemTextWidth = 0
  private var textHeight = 0
  private var textAscent = 0
  private var labelForeground: Color = UIUtil.getLabelForeground()
  private var stroke = BasicStroke(1f)
  private val idePath = Path2D.Float(Path2D.WIND_NON_ZERO, CpuSnapshot.HISTORY + 2)
  private val systemPath = Path2D.Float(Path2D.WIND_NON_ZERO, CpuSnapshot.HISTORY + 2)

  @Volatile
  private var missedRepaint = false
  private var window: Window? = null
  private val windowListener = object : WindowAdapter() {
    override fun windowActivated(e: WindowEvent) = repaintIfMissed()
    override fun windowDeiconified(e: WindowEvent) = repaintIfMissed()
  }

  @Volatile
  private var popup: CpuPopup? = null
  private val hoverTimer = Timer(HOVER_DELAY_MS) { openPopup() }.apply { isRepeats = false }

  init {
    isOpaque = false
    addMouseListener(object : MouseAdapter() {
      override fun mouseEntered(e: MouseEvent) = hoverTimer.restart()
      override fun mouseExited(e: MouseEvent) = hoverTimer.stop()
    })
    updateUI()
  }

  override fun updateUI() {
    super.updateUI()
    textFont = JBUI.Fonts.label()
    graphWidth = JBUI.scale(48)
    gap = JBUI.scale(6)
    pad = JBUI.scale(3)
    arc = JBUI.scale(4)
    stroke = BasicStroke(JBUIScale.scale(1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    val fm = getFontMetrics(textFont)
    ideTextWidth = fm.stringWidth(PhantomCpuBundle.message("widget.ide", 100)) + 2 * pad
    systemTextWidth = fm.stringWidth(PhantomCpuBundle.message("widget.system", 100))
    textHeight = fm.height
    textAscent = fm.ascent
    labelForeground = UIUtil.getLabelForeground()
    revalidate()
  }

  override fun getPreferredSize(): Dimension =
    Dimension(pad + graphWidth + gap + ideTextWidth + gap + systemTextWidth + pad, JBUI.scale(20))

  override fun getMinimumSize(): Dimension = preferredSize
  override fun getMaximumSize(): Dimension = preferredSize

  override fun sampled(snapshot: CpuSnapshot, changed: Boolean) {
    popup?.let { p -> SwingUtilities.invokeLater { if (popup === p) p.update(snapshot) } }
    if (!changed) return
    if (!isShowing) return
    val w = window
    if (w == null || !w.isActive || (w is Frame && w.extendedState and Frame.ICONIFIED != 0)) {
      missedRepaint = true
      return
    }
    repaint()
  }

  private fun repaintIfMissed() {
    if (missedRepaint) {
      missedRepaint = false
      repaint()
    }
  }

  override fun addNotify() {
    super.addNotify()
    window = SwingUtilities.getWindowAncestor(this)?.also { it.addWindowListener(windowListener) }
  }

  override fun removeNotify() {
    window?.removeWindowListener(windowListener)
    window = null
    hoverTimer.stop()
    closePopup()
    super.removeNotify()
  }

  override fun paintComponent(g: Graphics) {
    val s = sampler.snapshot
    val g2 = g as Graphics2D
    val h = height
    var x = pad

    // Graph: system behind, IDE in front, 0% at the bottom
    val top = pad
    val bottom = h - pad
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g2.stroke = stroke
    g2.color = GRID
    g2.drawLine(x, bottom, x + graphWidth, bottom)
    buildPath(systemPath, s.systemHistory, x, top, bottom)
    g2.color = SYSTEM
    g2.draw(systemPath)
    buildPath(idePath, s.ideHistory, x, top, bottom)
    g2.color = IDE
    g2.draw(idePath)
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    x += graphWidth + gap

    // Numbers
    if (s.ideText.isEmpty()) return
    UISettings.setupAntialiasing(g2)
    g2.font = textFont
    val textTop = (h - textHeight) / 2
    val baseline = textTop + textAscent
    if (s.high) {
      g2.color = HIGH_BACKGROUND
      g2.fillRoundRect(x, textTop, ideTextWidth, textHeight, arc, arc)
      g2.color = HIGH_FOREGROUND
    }
    else {
      g2.color = IDE
    }
    g2.drawString(s.ideText, x + pad, baseline)
    x += ideTextWidth + gap
    g2.color = labelForeground
    g2.drawString(s.systemText, x, baseline)
  }

  private fun buildPath(path: Path2D.Float, history: IntArray, left: Int, top: Int, bottom: Int) {
    path.reset()
    val n = history.size
    var started = false
    for (i in 0 until n) {
      val v = history[i]
      if (v < 0) continue
      val px = left + graphWidth * i / (n - 1).toFloat()
      val py = bottom - (bottom - top) * v / 100f
      if (started) path.lineTo(px, py) else { path.moveTo(px, py); started = true }
    }
  }

  private fun openPopup() {
    if (popup != null || !isShowing) return
    val p = CpuPopup(this, sampler.snapshot) { popup = null; sampler.releaseDetail() }
    popup = p
    sampler.requestDetail()
    p.show()
  }

  fun closePopup() {
    popup?.close()
  }

  private companion object {
    const val HOVER_DELAY_MS = 300
    val IDE: Color = JBColor(0x3574F0, 0x548AF7)
    val SYSTEM: Color = JBColor(0x9AA0A6, 0x6F737A)
    val GRID: Color = JBColor(0xDFE1E5, 0x43454A)
    val HIGH_BACKGROUND: Color = JBColor(0xF2A13D, 0xC77D23)
    val HIGH_FOREGROUND: Color = JBColor(0xFFFFFF, 0x1E1F22)
  }
}

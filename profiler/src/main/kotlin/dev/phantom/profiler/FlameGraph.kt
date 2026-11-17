// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.profiler

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StartupUiUtil
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.Scrollable
import javax.swing.SwingConstants
import kotlin.math.max

/**
 * A flame graph laid out top-down: the focused node spans the full width, each child takes the share of its parent's
 * weight, and the focused node's ancestors sit above it at full width so a click on one zooms back out.
 * Click a frame to zoom into it, double-click to open its source.
 */
class FlameGraph(
  private val root: CallNode,
  private val describe: (Long) -> String,
  private val isProjectClass: (String) -> Boolean,
  private val onNavigate: (Frame) -> Unit,
) : JComponent(), Scrollable {
  private var focus: CallNode = root
  private var boxes: List<Pair<Rectangle, CallNode>> = emptyList()
  private val rowHeight get() = JBUI.scale(18)

  init {
    toolTipText = ""
    val mouse = object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        val node = nodeAt(e.x, e.y) ?: return
        if (e.clickCount >= 2) node.frame?.let(onNavigate)
        else zoom(node)
      }
    }
    addMouseListener(mouse)
  }

  /** Zooms to [node]; the root (or a click on the focused node itself) shows everything again. */
  fun zoom(node: CallNode) {
    focus = if (node === focus) node.parent ?: root else node
    revalidate()
    repaint()
  }

  fun resetZoom() {
    focus = root
    revalidate()
    repaint()
  }

  override fun getToolTipText(e: MouseEvent): String? {
    val node = nodeAt(e.x, e.y) ?: return null
    val frame = node.frame ?: return null
    val share = if (root.total > 0) node.total * 100.0 / root.total else 0.0
    return "<html><b>${escape(frame.shortName)}</b><br>${escape(frame.className)}<br>" +
           PhantomProfilerBundle.message("flame.tooltip", "%.1f".format(share), describe(node.total), describe(node.self)) + "</html>"
  }

  internal fun nodeAt(x: Int, y: Int): CallNode? = boxes.firstOrNull { it.first.contains(x, y) }?.second

  override fun getPreferredSize(): Dimension {
    val ancestors = focus.path().size - (if (focus.frame != null) 1 else 0)
    val rows = ancestors + (focus.maxDepth() - focus.depth) + 1
    return Dimension(JBUI.scale(400), max(rows, 1) * rowHeight)
  }

  override fun paintComponent(g: Graphics) {
    val g2 = g as Graphics2D
    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g2.color = UIUtil.getPanelBackground()
    g2.fillRect(0, 0, width, height)
    val laid = ArrayList<Pair<Rectangle, CallNode>>()
    layout(width).forEach { (box, node) ->
      laid.add(box to node)
      paintBox(g2, box, node)
    }
    boxes = laid
  }

  /** Boxes for [width] pixels; frames narrower than a pixel are left out. */
  internal fun layout(width: Int): List<Pair<Rectangle, CallNode>> {
    val result = ArrayList<Pair<Rectangle, CallNode>>()
    var row = 0
    for (ancestor in focus.path().dropLast(if (focus.frame != null) 1 else 0)) {
      result.add(Rectangle(0, row++ * rowHeight, width, rowHeight - 1) to ancestor)
    }
    val total = focus.total.takeIf { it > 0 } ?: return result
    fun place(node: CallNode, x: Double, w: Double, r: Int) {
      if (w < 1.0) return
      if (node.frame != null) result.add(Rectangle(x.toInt(), r * rowHeight, max(w.toInt() - 1, 1), rowHeight - 1) to node)
      var childX = x
      val nextRow = if (node.frame != null) r + 1 else r
      for (child in node.children) {
        val cw = w * child.total / node.total
        place(child, childX, cw, nextRow)
        childX += cw
      }
    }
    place(focus, 0.0, width.toDouble() * focus.total / total, row)
    return result
  }

  private fun paintBox(g: Graphics2D, box: Rectangle, node: CallNode) {
    val frame = node.frame ?: return
    g.color = colorOf(frame, node === focus)
    g.fillRect(box.x, box.y, box.width, box.height)
    if (box.width < JBUI.scale(30)) return
    g.font = JBUI.Fonts.smallFont()
    g.color = JBColor(Color(0x1E1F22), Color(0x1E1F22))
    val metrics = g.fontMetrics
    val padding = JBUI.scale(3)
    val text = fit(frame.shortName, box.width - 2 * padding) { metrics.stringWidth(it) } ?: return
    g.drawString(text, box.x + padding, box.y + (box.height + metrics.ascent - metrics.descent) / 2)
  }

  /** The project's code warm, JDK cool, Kotlin violet, other libraries sand; a stable shade per class. */
  private fun colorOf(frame: Frame, focused: Boolean): Color {
    val name = frame.className
    val shade = (name.hashCode() and 0x1F) - 16
    val base = when {
      isProjectClass(name) -> Color(0xF0, 0x9A, 0x4A)
      name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.") || name.startsWith("com.sun.") ->
        Color(0x8F, 0xB4, 0xD9)
      name.startsWith("kotlin.") || name.startsWith("kotlinx.") -> Color(0xB9, 0x9C, 0xE8)
      else -> Color(0xE3, 0xC8, 0x7E)
    }
    val c = Color((base.red + shade).coerceIn(0, 255), (base.green + shade).coerceIn(0, 255), (base.blue + shade).coerceIn(0, 255))
    return if (focused) c.brighter() else if (StartupUiUtil.isDarkTheme) c.darker() else c
  }

  override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
  override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = rowHeight
  override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
    if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
  override fun getScrollableTracksViewportWidth(): Boolean = true
  override fun getScrollableTracksViewportHeight(): Boolean = false

  companion object {
    /** The longest prefix of [text] (with an ellipsis) that fits [width], or null when not even one letter does. */
    internal fun fit(text: String, width: Int, measure: (String) -> Int): String? {
      if (measure(text) <= width) return text
      var end = text.length - 1
      while (end > 0 && measure(text.take(end) + "…") > width) end--
      return if (end > 0) text.take(end) + "…" else null
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
  }
}

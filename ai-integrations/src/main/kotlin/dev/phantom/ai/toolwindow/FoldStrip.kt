// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * What is left of the folded tree column: a thin strip with an arrow and [title], read bottom to top like a tool window
 * stripe when [sideways] (the tree sits left of the terminals), left to right when the tree sits above them. A click
 * runs [onOpen].
 */
class FoldStrip(private val title: String, tooltip: String, private val onOpen: () -> Unit) : JComponent() {
  var sideways: Boolean = true
    set(value) { field = value; revalidate(); repaint() }

  private var hover = false

  init {
    name = "toolwindow.foldStrip"
    updateUI()
    toolTipText = tooltip
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) = onOpen()
      override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
      override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
    })
  }

  /** A plain JComponent has no font of its own until it is in a hierarchy; the strip measures itself before that. */
  override fun updateUI() {
    super.updateUI()
    font = JBUI.Fonts.label()
  }

  private val icon get() = if (sideways) AllIcons.General.ChevronRight else AllIcons.General.ChevronDown

  override fun getPreferredSize(): Dimension {
    val thick = JBUI.scale(22)
    val long = JBUI.scale(8) + icon.iconWidth + JBUI.scale(6) + getFontMetrics(font).stringWidth(title) + JBUI.scale(8)
    return if (sideways) Dimension(thick, long) else Dimension(long, thick)
  }

  override fun getMinimumSize(): Dimension = if (sideways) Dimension(preferredSize.width, 0) else Dimension(0, preferredSize.height)

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.color = if (hover) JBUI.CurrentTheme.ActionButton.hoverBackground() else UIUtil.getPanelBackground()
      g2.fillRect(0, 0, width, height)
      g2.color = JBColor.border()
      if (sideways) g2.drawLine(width - 1, 0, width - 1, height) else g2.drawLine(0, height - 1, width, height - 1)
      g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      g2.font = font
      g2.color = UIUtil.getLabelForeground()
      val fm = g2.fontMetrics
      val pad = JBUI.scale(8)
      if (sideways) {
        icon.paintIcon(this, g2, (width - icon.iconWidth) / 2, pad)
        // bottom to top, starting under the arrow
        g2.translate((width + fm.ascent - fm.descent) / 2, pad + icon.iconHeight + JBUI.scale(6) + fm.stringWidth(title))
        g2.rotate(-Math.PI / 2)
        g2.drawString(title, 0, 0)
      } else {
        icon.paintIcon(this, g2, pad, (height - icon.iconHeight) / 2)
        g2.drawString(title, pad + icon.iconWidth + JBUI.scale(6), (height + fm.ascent - fm.descent) / 2)
      }
    } finally {
      g2.dispose()
    }
  }
}

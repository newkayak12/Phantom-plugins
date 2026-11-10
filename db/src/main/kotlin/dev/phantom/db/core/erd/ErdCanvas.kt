// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.erd

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.phantom.db.core.ui.DbDiagramPanel
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel

/** The design on a pannable, zoomable surface. It only draws and reports gestures; the editor changes the document. */
internal class ErdCanvas(private val listener: Listener) : JPanel() {
  interface Listener {
    /** A click on a table, or null for a click on the empty canvas. */
    fun selected(table: String?)
    fun moved(table: String, x: Int, y: Int)
    fun linked(child: String, parent: String)
  }

  var document: ErdDocument = ErdDocument()
    set(value) { field = value; repaint() }
  var selection: String? = null
    set(value) { field = value; repaint() }
  var logicalNames: Boolean = false
    set(value) { field = value; repaint() }
  /** While on, dragging from one table to another draws a relation instead of moving the table. */
  var linking: Boolean = false

  private val titleFont: Font get() = JBUI.Fonts.label().deriveFont(Font.BOLD)
  private val columnFont: Font get() = JBUI.Fonts.label()
  private var zoom = 1.0
  private var offset = Point(0, 0)
  private var dragged: String? = null
  private var draggedTo: Point? = null
  private var linkEnd: Point? = null

  init {
    background = UIUtil.getPanelBackground()
    isFocusable = true
    val mouse = object : MouseAdapter() {
      var pressedAt: Point? = null
      var last: Point? = null
      var grab: Point? = null
      var moved = false

      override fun mousePressed(e: MouseEvent) {
        requestFocusInWindow()
        pressedAt = e.point
        last = e.point
        moved = false
        val at = toModel(e.point)
        val hit = tableAt(at)
        dragged = hit?.name
        grab = hit?.let { Point(at.x - it.x, at.y - it.y) }
      }

      override fun mouseDragged(e: MouseEvent) {
        val from = last ?: return
        if (!moved && (pressedAt?.distance(e.point) ?: 0.0) < JBUI.scale(3)) return
        moved = true
        val at = toModel(e.point)
        when {
          dragged != null && linking -> linkEnd = at
          dragged != null -> draggedTo = Point(at.x - grab!!.x, at.y - grab!!.y)
          else -> offset.translate(e.x - from.x, e.y - from.y)
        }
        last = e.point
        repaint()
      }

      override fun mouseReleased(e: MouseEvent) {
        if (pressedAt == null) return
        val name = dragged
        val to = draggedTo
        val target = tableAt(toModel(e.point))?.name
        val wasLinking = linkEnd != null
        pressedAt = null
        last = null
        dragged = null
        draggedTo = null
        linkEnd = null
        when {
          !moved -> listener.selected(name)
          wasLinking && name != null && target != null -> listener.linked(name, target)
          name != null && to != null -> listener.moved(name, to.x, to.y)
        }
        repaint()
      }

      override fun mouseWheelMoved(e: MouseWheelEvent) = zoomBy(if (e.preciseWheelRotation < 0) 1.1 else 1 / 1.1, e.point)
    }
    addMouseListener(mouse)
    addMouseMotionListener(mouse)
    addMouseWheelListener(mouse)
  }

  private fun zoomBy(factor: Double, at: Point) {
    val before = toModel(at)
    zoom = (zoom * factor).coerceIn(0.1, 4.0)
    offset = Point((at.x - before.x * zoom).toInt(), (at.y - before.y * zoom).toInt())
    repaint()
  }

  private fun toModel(p: Point) = Point(((p.x - offset.x) / zoom).toInt(), ((p.y - offset.y) / zoom).toInt())

  /** The point of the design at the middle of what is on screen, where a new table goes. */
  fun center(): Point = toModel(Point(width / 2, height / 2))

  private fun tableAt(p: Point): ErdTable? = document.tables.lastOrNull { box(it).contains(p) }

  private fun lineHeight(fm: FontMetrics) = fm.height + JBUI.scale(2)

  private fun shownName(name: String, logical: String) = if (logicalNames) logical.ifBlank { name } else name

  private fun isForeign(t: ErdTable, c: ErdColumn) = document.relations.any { it.table == t.name && c.name in it.columns }

  private fun columnText(t: ErdTable, c: ErdColumn): String {
    val mark = listOfNotNull(if (c.primaryKey) "PK" else null, if (isForeign(t, c)) "FK" else null).joinToString(",")
    return mark.ifEmpty { "  " }.padEnd(6) + shownName(c.name, c.logicalName) + "  " + c.type
  }

  /** Where [t] is drawn: at the mouse while it is being dragged. */
  private fun box(t: ErdTable): Rectangle {
    val title = getFontMetrics(titleFont)
    val column = getFontMetrics(columnFont)
    val pad = JBUI.scale(8)
    val width = maxOf(title.stringWidth(shownName(t.name, t.logicalName)), t.columns.maxOfOrNull { column.stringWidth(columnText(t, it)) } ?: 0) + 2 * pad
    val position = draggedTo?.takeIf { t.name == dragged } ?: Point(t.x, t.y)
    return Rectangle(position.x, position.y, maxOf(width, JBUI.scale(120)), lineHeight(title) + pad + t.columns.size * lineHeight(column) + pad)
  }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    val g2 = g.create() as Graphics2D
    try {
      g2.translate(offset.x, offset.y)
      g2.scale(zoom, zoom)
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      paintRelations(g2)
      document.tables.forEach { paintTable(g2, it) }
    }
    finally {
      g2.dispose()
    }
  }

  private fun paintRelations(g: Graphics2D) {
    g.stroke = BasicStroke(1.2f)
    val boxes = document.tables.associate { it.name to box(it) }
    for (r in document.relations) {
      val from = boxes[r.table] ?: continue
      val to = boxes[r.refTable] ?: continue
      g.color = DbDiagramPanel.LINE
      DbDiagramPanel.drawLink(g, from, to, r.table == r.refTable)
      g.color = UIUtil.getContextHelpForeground()
      g.font = columnFont
      g.drawString(if (document.isOneToOne(r)) "1:1" else "1:N", (from.centerX + to.centerX).toInt() / 2, (from.y + to.y) / 2 + JBUI.scale(8))
    }
    val end = linkEnd ?: return
    val start = dragged?.let { boxes[it] } ?: return
    g.color = SELECTED
    g.drawLine(start.centerX.toInt(), start.centerY.toInt(), end.x, end.y)
  }

  private fun paintTable(g: Graphics2D, t: ErdTable) {
    val r = box(t)
    val title = g.getFontMetrics(titleFont)
    val column = g.getFontMetrics(columnFont)
    val pad = JBUI.scale(8)
    g.color = DbDiagramPanel.BOX
    g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8)
    g.color = DbDiagramPanel.HEADER
    g.fillRoundRect(r.x, r.y, r.width, lineHeight(title) + pad / 2, 8, 8)
    val chosen = t.name == selection
    g.color = if (chosen) SELECTED else DbDiagramPanel.BORDER
    g.stroke = BasicStroke(if (chosen) 2f else 1f)
    g.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8)
    g.color = UIUtil.getLabelForeground()
    g.font = titleFont
    g.drawString(shownName(t.name, t.logicalName), r.x + pad, r.y + pad / 4 + title.ascent)
    g.font = columnFont
    var y = r.y + lineHeight(title) + pad + column.ascent
    for (c in t.columns) {
      g.color = if (c.primaryKey) DbDiagramPanel.KEY else if (isForeign(t, c)) DbDiagramPanel.FOREIGN else UIUtil.getLabelForeground()
      g.drawString(columnText(t, c), r.x + pad, y)
      y += lineHeight(column)
    }
  }

  private companion object {
    val SELECTED = JBColor(Color(0x3574F0), Color(0x548AF7))
  }
}

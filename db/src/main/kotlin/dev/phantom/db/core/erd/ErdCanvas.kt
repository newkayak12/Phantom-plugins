// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.erd

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.phantom.db.core.ui.DbCardRow
import dev.phantom.db.core.ui.DbTableCard
import java.awt.BasicStroke
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

  private var zoom = 1.0
  private var offset = Point(0, 0)
  private var dragged: String? = null
  private var draggedTo: Point? = null
  private var linkEnd: Point? = null

  init {
    background = DbTableCard.CANVAS
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

  private fun shownName(name: String, logical: String) = if (logicalNames) logical.ifBlank { name } else name

  private fun isForeign(t: ErdTable, c: ErdColumn) = document.relations.any { it.table == t.name && c.name in it.columns }

  private fun rows(t: ErdTable) = t.columns.map { DbCardRow(shownName(it.name, it.logicalName), it.type, it.primaryKey, isForeign(t, it)) }

  /** Where [t] is drawn: at the mouse while it is being dragged. */
  private fun box(t: ErdTable): Rectangle {
    val size = DbTableCard.size(getFontMetrics(DbTableCard.titleFont), getFontMetrics(DbTableCard.columnFont), shownName(t.name, t.logicalName), rows(t))
    val position = draggedTo?.takeIf { t.name == dragged } ?: Point(t.x, t.y)
    return Rectangle(position.x, position.y, size.width, size.height)
  }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      DbTableCard.paintGrid(g2, width, height, offset, zoom)
      g2.translate(offset.x, offset.y)
      g2.scale(zoom, zoom)
      paintRelations(g2)
      document.tables.forEach { DbTableCard.paint(g2, box(it), shownName(it.name, it.logicalName), rows(it), it.name == selection) }
    }
    finally {
      g2.dispose()
    }
  }

  private fun paintRelations(g: Graphics2D) {
    val title = g.getFontMetrics(DbTableCard.titleFont)
    val column = g.getFontMetrics(DbTableCard.columnFont)
    val tables = document.tables.associateBy { it.name }
    val boxes = document.tables.associate { it.name to box(it) }
    for (r in document.relations) {
      val from = boxes[r.table] ?: continue
      val to = boxes[r.refTable] ?: continue
      val fromRow = tables[r.table]?.columns?.indexOfFirst { it.name == r.columns.firstOrNull() } ?: -1
      val toRow = tables[r.refTable]?.columns?.indexOfFirst { it.name == r.refColumns.firstOrNull() } ?: -1
      val oneToOne = document.isOneToOne(r)
      DbTableCard.drawLink(g, from, DbTableCard.rowY(from, title, column, fromRow), to, DbTableCard.rowY(to, title, column, toRow),
                           oneToOne, if (oneToOne) "1 : 1" else "1 : N", dashed = false)
    }
    val end = linkEnd ?: return
    val start = dragged?.let { boxes[it] } ?: return
    g.color = DbTableCard.SELECTED
    g.stroke = BasicStroke(1.5f)
    g.drawLine(start.centerX.toInt(), start.centerY.toInt(), end.x, end.y)
  }
}

// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.geom.Ellipse2D

/** One column line of a table card. */
internal data class DbCardRow(val name: String, val type: String, val primaryKey: Boolean, val foreign: Boolean)

/**
 * How Diagram and ERD Design draw a table: a header band with the name, then one line per column with a key icon for
 * the primary key and a link icon for foreign keys, the name, and the type right-aligned. Relations run between the
 * column lines they join, with a crow's foot on the many side.
 */
internal object DbTableCard {
  val titleFont: Font get() = JBUI.Fonts.label().deriveFont(Font.BOLD)
  val columnFont: Font get() = JBUI.Fonts.label()

  private val pad: Int get() = JBUI.scale(8)
  private val icon: Int get() = JBUI.scale(19)
  private val gap: Int get() = JBUI.scale(18)
  private val arc: Int get() = JBUI.scale(10)

  fun headerHeight(title: FontMetrics): Int = title.height + JBUI.scale(8)
  fun rowHeight(column: FontMetrics): Int = column.height + JBUI.scale(3)

  fun size(title: FontMetrics, column: FontMetrics, name: String, rows: List<DbCardRow>): Dimension {
    val names = rows.maxOfOrNull { column.stringWidth(it.name) } ?: 0
    val types = rows.maxOfOrNull { column.stringWidth(it.type) } ?: 0
    val width = maxOf(title.stringWidth(name) + 2 * pad, pad + icon + names + gap + types + pad, JBUI.scale(150))
    return Dimension(width, headerHeight(title) + pad / 2 + rows.size * rowHeight(column) + pad / 2)
  }

  /** The middle of column line [index] of a card at [r]; the header's middle for -1. */
  fun rowY(r: Rectangle, title: FontMetrics, column: FontMetrics, index: Int): Int =
    if (index < 0) r.y + headerHeight(title) / 2
    else r.y + headerHeight(title) + pad / 2 + index * rowHeight(column) + rowHeight(column) / 2

  fun paint(g: Graphics2D, r: Rectangle, name: String, rows: List<DbCardRow>, selected: Boolean) {
    val title = g.getFontMetrics(titleFont)
    val column = g.getFontMetrics(columnFont)
    val header = headerHeight(title)
    g.color = SHADOW
    g.fillRoundRect(r.x + 1, r.y + JBUI.scale(3), r.width, r.height, arc, arc)
    g.color = BOX
    g.fillRoundRect(r.x, r.y, r.width, r.height, arc, arc)
    val clip = g.clip
    g.clipRect(r.x, r.y, r.width + 1, header)
    g.color = HEADER
    g.fillRoundRect(r.x, r.y, r.width, r.height, arc, arc)
    g.clip = clip
    g.color = BORDER
    g.stroke = BasicStroke(1f)
    g.drawLine(r.x, r.y + header, r.x + r.width, r.y + header)
    g.color = if (selected) SELECTED else BORDER
    g.stroke = BasicStroke(if (selected) 2f else 1f)
    g.drawRoundRect(r.x, r.y, r.width, r.height, arc, arc)

    g.font = titleFont
    g.color = TITLE
    g.drawString(name, r.x + pad, r.y + (header - title.height) / 2 + title.ascent)

    g.font = columnFont
    for ((i, row) in rows.withIndex()) {
      val middle = rowY(r, title, column, i)
      val baseline = middle - column.height / 2 + column.ascent
      val color = if (row.primaryKey) KEY else if (row.foreign) FOREIGN else TEXT
      if (row.primaryKey) paintKey(g, r.x + pad, middle) else if (row.foreign) paintLinkIcon(g, r.x + pad, middle)
      g.color = color
      g.drawString(row.name, r.x + pad + icon, baseline)
      g.color = TYPE
      g.drawString(row.type, r.x + r.width - pad - column.stringWidth(row.type), baseline)
    }
  }

  private fun paintKey(g: Graphics2D, x: Int, y: Int) {
    val s = JBUI.scale(1).toFloat()
    g.color = KEY
    g.stroke = BasicStroke(1.6f * s)
    g.draw(Ellipse2D.Float(x.toFloat(), y - 3.5f * s, 7f * s, 7f * s))
    g.drawLine(x + JBUI.scale(7), y, x + JBUI.scale(12), y)
    g.drawLine(x + JBUI.scale(10), y, x + JBUI.scale(10), y + JBUI.scale(3))
    g.drawLine(x + JBUI.scale(12), y, x + JBUI.scale(12), y + JBUI.scale(3))
  }

  private fun paintLinkIcon(g: Graphics2D, x: Int, y: Int) {
    val s = JBUI.scale(1).toFloat()
    g.color = FOREIGN
    g.stroke = BasicStroke(1.4f * s)
    g.draw(Ellipse2D.Float(x.toFloat(), y - 2.5f * s, 6f * s, 5f * s))
    g.draw(Ellipse2D.Float(x + 5f * s, y - 2.5f * s, 6f * s, 5f * s))
  }

  /** A dotted grid under the design, in component coordinates, moving with [offset] and [zoom]. */
  fun paintGrid(g: Graphics2D, width: Int, height: Int, offset: Point, zoom: Double) {
    val step = JBUI.scale(16) * zoom
    if (step < 6) return
    g.color = GRID
    val dot = maxOf(1, JBUI.scale(1))
    var x = Math.floorMod(offset.x, step.toInt().coerceAtLeast(1)).toDouble()
    while (x < width) {
      var y = Math.floorMod(offset.y, step.toInt().coerceAtLeast(1)).toDouble()
      while (y < height) {
        g.fillRect(x.toInt(), y.toInt(), dot, dot)
        y += step
      }
      x += step
    }
  }

  /**
   * A relation from the referencing card [from] (at its column line [fromY]) to the referenced card [to] (at [toY]):
   * an orthogonal line with a crow's foot at [from] unless [oneToOne], a bar at [to], and [label] by its middle.
   */
  fun drawLink(g: Graphics2D, from: Rectangle, fromY: Int, to: Rectangle, toY: Int, oneToOne: Boolean, label: String?, dashed: Boolean) {
    g.color = LINE
    g.stroke = if (dashed) BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 3f), 0f) else BasicStroke(1.2f)
    val reach = JBUI.scale(20)
    if (from == to) {
      val x = from.x + from.width
      g.drawPolyline(intArrayOf(x, x + reach, x + reach, x), intArrayOf(fromY, fromY, toY, toY), 4)
      return
    }
    val rightward = to.x >= from.x + from.width
    val leftward = to.x + to.width <= from.x
    val (x1, x2, mid) = when {
      leftward -> Triple(from.x, to.x + to.width, (from.x + to.x + to.width) / 2)
      rightward -> Triple(from.x + from.width, to.x, (from.x + from.width + to.x) / 2)
      // one card above the other: go around on the left
      else -> Triple(from.x, to.x, minOf(from.x, to.x) - reach)
    }
    g.drawPolyline(intArrayOf(x1, mid, mid, x2), intArrayOf(fromY, fromY, toY, toY), 4)
    g.stroke = BasicStroke(1.2f)
    val out1 = if (mid >= x1) 1 else -1
    val out2 = if (mid >= x2) 1 else -1
    val foot = JBUI.scale(9)
    val spread = JBUI.scale(5)
    // the one side: a bar across the line
    g.drawLine(x2 + out2 * JBUI.scale(6), toY - spread, x2 + out2 * JBUI.scale(6), toY + spread)
    if (oneToOne) g.drawLine(x1 + out1 * JBUI.scale(6), fromY - spread, x1 + out1 * JBUI.scale(6), fromY + spread)
    else {
      g.drawLine(x1 + out1 * foot, fromY, x1, fromY - spread)
      g.drawLine(x1 + out1 * foot, fromY, x1, fromY + spread)
    }
    if (label != null) {
      val fm = g.getFontMetrics(columnFont)
      g.font = columnFont
      g.color = TYPE
      g.drawString(label, mid + JBUI.scale(4), (fromY + toY) / 2 + fm.ascent / 2)
    }
  }

  val CANVAS: JBColor = JBColor(Color(0xF7F8FA), Color(0x1E1F22))
  val BOX: JBColor = JBColor(Color(0xFFFFFF), Color(0x2B2D30))
  val HEADER: JBColor = JBColor(Color(0xDDE7F5), Color(0x3D4A63))
  val TITLE: JBColor = JBColor(Color(0x1F2D45), Color(0xF0F2F5))
  val BORDER: JBColor = JBColor(Color(0xB4BECC), Color(0x4E5157))
  val LINE: JBColor = JBColor(Color(0x6A7A90), Color(0x8C9AAE))
  val KEY: JBColor = JBColor(Color(0xA06A00), Color(0xE8B24A))
  val FOREIGN: JBColor = JBColor(Color(0x2B6CB0), Color(0x56A8F5))
  val TEXT: JBColor = JBColor(Color(0x1F2328), Color(0xDFE1E5))
  val TYPE: JBColor = JBColor(Color(0x7A7E85), Color(0x8C8F94))
  val GRID: JBColor = JBColor(Color(0xD5D9E0), Color(0x393B40))
  val SELECTED: JBColor = JBColor(Color(0x3574F0), Color(0x548AF7))
  private val SHADOW = Color(0, 0, 0, 40)
}

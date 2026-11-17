// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.ui

import dev.phantom.ai.visualization.model.Floor
import dev.phantom.ai.visualization.model.OfficeModel
import dev.phantom.ai.visualization.render.OfficeRenderer
import dev.phantom.ai.visualization.render.PickerLayout
import dev.phantom.ai.visualization.theme.Theme
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel

/** The floors of the building as cards, top floor first, with the per-state counts; a click selects a floor. */
class FloorPickerView(private var renderer: OfficeRenderer, private val onSelect: (String) -> Unit) : JComponent() {
  var floors: List<Floor> = emptyList()
    private set
  var selectedKey: String? = null
  private var hoverKey: String? = null

  init {
    isOpaque = true
    preferredSize = Dimension(PREFERRED_WIDTH, PickerLayout.height(0))
    val m = object : MouseAdapter() {
      override fun mouseReleased(e: MouseEvent) {
        keyAt(e.x, e.y)?.let(onSelect)
      }

      override fun mouseMoved(e: MouseEvent) {
        val k = keyAt(e.x, e.y)
        if (k != hoverKey) {
          hoverKey = k
          repaint()
        }
      }

      override fun mouseExited(e: MouseEvent) {
        hoverKey = null
        repaint()
      }
    }
    addMouseListener(m)
    addMouseMotionListener(m)
  }

  fun setRenderer(r: OfficeRenderer) {
    renderer = r
    repaint()
  }

  fun setFloors(f: List<Floor>, selected: String?) {
    floors = f
    selectedKey = selected
    preferredSize = Dimension(PREFERRED_WIDTH, PickerLayout.height(f.size))
    revalidate()
    repaint()
  }

  /** The floor key whose card contains ([x], [y]). */
  fun keyAt(x: Int, y: Int): String? = floors.indices.firstOrNull { PickerLayout.card(it, width).contains(x, y) }?.let { floors[it].key }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.color = renderer.theme.palette.surface
      g2.fillRect(0, 0, width, height)
      renderer.drawFloorPicker(g2, floors, selectedKey, width, hoverKey)
    } finally {
      g2.dispose()
    }
  }

  companion object {
    const val PREFERRED_WIDTH = 200
  }
}

/** The four states with their bubble icon, below the floor picker. */
class LegendView(private var renderer: OfficeRenderer) : JComponent() {
  init {
    isOpaque = true
    preferredSize = Dimension(FloorPickerView.PREFERRED_WIDTH, renderer.legendHeight())
  }

  fun setRenderer(r: OfficeRenderer) {
    renderer = r
    repaint()
  }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.color = renderer.theme.palette.surface
      g2.fillRect(0, 0, width, height)
      renderer.drawLegend(g2, width, 0)
    } finally {
      g2.dispose()
    }
  }
}

/** The AI Visualization: floor picker and legend in a column on the left, the canvas of the selected floor on the right. */
class OfficePanel(theme: Theme, actions: OfficeActions, clock: () -> Long = System::currentTimeMillis) : JPanel(BorderLayout()) {
  private var renderer = OfficeRenderer(theme)
  val canvas = OfficeCanvas(theme, actions, clock)
  val picker = FloorPickerView(renderer) { select(it) }
  val legend = LegendView(renderer)
  private val west = JPanel(BorderLayout())

  var model: OfficeModel = OfficeModel(emptyList())
    private set
  var selectedKey: String? = null
    private set

  init {
    west.background = theme.palette.surface
    west.add(picker, BorderLayout.CENTER)
    west.add(legend, BorderLayout.SOUTH)
    add(west, BorderLayout.WEST)
    add(canvas, BorderLayout.CENTER)
  }

  /**
   * Replaces the data; keeps the selected floor when it still exists, else the floor (or the project's main floor) [preferredKey]
   * names, else the lowest floor.
   */
  fun setModel(m: OfficeModel, preferredKey: String? = null) {
    model = m
    val key = listOf(selectedKey, preferredKey).firstOrNull { it != null && m.floor(it) != null }
      ?: m.floors.firstOrNull { it.projectKey == preferredKey }?.key ?: m.floors.firstOrNull()?.key
    selectedKey = key
    picker.setFloors(m.floorsTopDown, key)
    canvas.setFloor(m.floor(key))
  }

  fun select(key: String) {
    if (model.floor(key) == null || key == selectedKey) return
    selectedKey = key
    picker.selectedKey = key
    picker.repaint()
    canvas.setFloor(model.floor(key))
  }

  fun setTheme(t: Theme) {
    renderer = OfficeRenderer(t)
    picker.setRenderer(renderer)
    legend.setRenderer(renderer)
    west.background = t.palette.surface
    canvas.setTheme(t)
  }

  fun dispose() = canvas.dispose()
}

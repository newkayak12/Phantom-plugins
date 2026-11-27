// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.ui

import dev.phantom.ai.api.AiAgent
import dev.phantom.ai.visualization.AiVisualizationBundle.message
import dev.phantom.ai.visualization.model.Floor
import dev.phantom.ai.visualization.model.OfficeLayout
import dev.phantom.ai.visualization.model.Rect
import dev.phantom.ai.visualization.model.Scene
import dev.phantom.ai.visualization.render.OfficeRenderer
import dev.phantom.ai.visualization.render.PagerLayout
import dev.phantom.ai.visualization.render.PaintResult
import dev.phantom.ai.visualization.render.PlacedHelper
import dev.phantom.ai.visualization.render.PlacedPerson
import dev.phantom.ai.visualization.theme.Theme
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.ToolTipManager

/** What a click on a person or an empty desk does; the editor wires it to PhantomAiApi, tests to a fake. */
interface OfficeActions {
  fun focus(tabId: String)
  fun start(worktreePath: String, agent: AiAgent)
}

/** What is under a point of the canvas. */
sealed interface Hit {
  data class PersonHit(val tabId: String) : Hit

  /** A helper (a running subagent) of the session [tabId]; a click focuses that session. */
  data class HelperHit(val tabId: String, val helperId: String) : Hit
  data class DeskHit(val worktreePath: String) : Hit
  data class PageHit(val delta: Int) : Hit
  object None : Hit
}

/** The room of one floor page: draws it, hit-tests it and handles ctrl+wheel zoom, drag pan, click, hover and the desk menu. */
class OfficeCanvas(
  theme: Theme,
  private val actions: OfficeActions,
  private val clock: () -> Long = System::currentTimeMillis,
) : JComponent() {
  var theme: Theme = theme
    private set
  private var renderer = OfficeRenderer(theme)
  private var layout = OfficeLayout(theme, renderer.atlas)

  var floor: Floor? = null
    private set
  var page = 0
    private set
  var scene: Scene = layout.layout(emptyList(), 0)
    private set

  /** Integer zoom, 1..[MAX_ZOOM]; follows the size of the canvas until the user zooms. */
  var zoom = 1
    private set
  var panX = 0
    private set
  var panY = 0
    private set
  private var userZoomed = false
  /** Wheel rotation not yet turned into a zoom step (zoom in is positive). */
  private var wheel = 0.0
  private var last: PaintResult = PaintResult(emptyList(), emptyList())

  val animation = Animation(this, { this.theme.animationPhases }, { this.theme.animationPeriodMs }) {
    refreshPeople()
    repaint()
  }

  /** The phase now drawn. */
  val phase: Int get() = animation.phase

  init {
    isOpaque = true
    putClientProperty(THEME_PROPERTY, theme.id)
    ToolTipManager.sharedInstance().registerComponent(this)
    val mouse = object : MouseAdapter() {
      private var pressed: Point? = null
      private var dragged = false
      private var startPanX = 0
      private var startPanY = 0

      override fun mousePressed(e: MouseEvent) {
        pressed = e.point
        dragged = false
        startPanX = panX
        startPanY = panY
      }

      override fun mouseDragged(e: MouseEvent) {
        val p = pressed ?: return
        if (!dragged && p.distance(e.point) < 4) return
        dragged = true
        panTo(startPanX + e.x - p.x, startPanY + e.y - p.y)
      }

      override fun mouseReleased(e: MouseEvent) {
        if (!dragged && e.button == MouseEvent.BUTTON1) click(e.x, e.y, e)
        pressed = null
      }

      override fun mouseMoved(e: MouseEvent) {
        cursor = if (hitTest(e.x, e.y) == Hit.None) Cursor.getDefaultCursor() else Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      }

      override fun mouseWheelMoved(e: MouseWheelEvent) {
        if (!(e.isControlDown || e.isMetaDown)) return
        // a trackpad sends fractions of a notch (wheelRotation 0) and an inertia tail: add them up, one step per whole notch
        // turning the other way drops what was left of the last swipe
        val delta = -e.preciseWheelRotation
        if (delta * wheel < 0) wheel = 0.0
        wheel += delta
        val steps = wheel.toInt()
        if (steps == 0) return
        wheel -= steps
        zoomBy(steps, e.x, e.y)
      }
    }
    addMouseListener(mouse)
    addMouseMotionListener(mouse)
    addMouseWheelListener(mouse)
  }

  // ---- data

  /** Shows [floor] (null: an empty room) at [page]; keeps pan and zoom when it is the same floor. */
  fun setFloor(floor: Floor?, page: Int = 0) {
    val same = floor?.key == this.floor?.key && floor != null
    this.floor = floor
    this.scene = layout.layout(floor?.areas ?: emptyList(), page)
    this.page = scene.page
    if (!same) {
      panX = 0
      panY = 0
    }
    fit()
    refreshPeople()
    repaint()
  }

  fun setTheme(t: Theme) {
    if (t.id == theme.id) return
    theme = t
    putClientProperty(THEME_PROPERTY, t.id)
    renderer = OfficeRenderer(t)
    layout = OfficeLayout(t, renderer.atlas)
    setFloor(floor, page)
  }

  fun showPage(delta: Int) = setFloor(floor, (page + delta).coerceIn(0, scene.pageCount - 1))

  fun dispose() = animation.dispose()

  // ---- view

  /**
   * Largest integer zoom at which the scene's desk areas ([Scene.focus]) fit the canvas, 1..[MAX_ZOOM]. A zoom that crops at most
   * [FIT_SLACK] of them still counts: that only cuts into the margin around the areas, and makes the people a step bigger.
   */
  fun fitZoom(): Int {
    val f = scene.focus
    val fit = kotlin.math.min(width.toDouble() / f.w, height.toDouble() / f.h) * (1 + FIT_SLACK)
    return min(MAX_ZOOM, max(1, fit.toInt()))
  }

  private fun fit() {
    if (!userZoomed) zoom = fitZoom()
  }

  override fun setBounds(x: Int, y: Int, w: Int, h: Int) {
    super.setBounds(x, y, w, h)
    fit()
    refreshPeople()  }

  /** The origin at zoom [z] before panning: the centre of the scene's focus at the centre of the canvas. */
  private fun baseX(z: Int) = width / 2 - (scene.focus.x * 2 + scene.focus.w) * z / 2
  private fun baseY(z: Int) = height / 2 - (scene.focus.y * 2 + scene.focus.h) * z / 2
  private fun originX() = baseX(zoom) + panX
  private fun originY() = baseY(zoom) + panY

  fun panTo(x: Int, y: Int) {
    val lx = scene.width * zoom
    val ly = scene.height * zoom
    panX = x.coerceIn(-lx, lx)
    panY = y.coerceIn(-ly, ly)
    refreshPeople()
    repaint()
  }

  /** Changes the zoom by [steps] whole steps (clamped), keeping the scene point under ([cx], [cy]) in place. */
  fun zoomBy(steps: Int, cx: Int = width / 2, cy: Int = height / 2) {
    val nz = (zoom + steps).coerceIn(1, MAX_ZOOM)
    if (nz == zoom) return
    val sx = (cx - originX()).toDouble() / zoom
    val sy = (cy - originY()).toDouble() / zoom
    zoom = nz
    userZoomed = true
    panX = (cx - sx * nz - baseX(nz)).toInt()
    panY = (cy - sy * nz - baseY(nz)).toInt()
    refreshPeople()
    repaint()
  }

  // ---- hit testing

  /** Whether the person frame [frame] drawn shrunk by [scale] (a subordinate's; 1 for a person) at [screen] is opaque at ([x], [y]). */
  private fun opaque(frame: String, scale: Int, screen: Rect, x: Int, y: Int): Boolean {
    // in the frame's own pixels, [detail] of them per scene pixel, as the renderer draws it (see OfficeRenderer.composeScale)
    val d = renderer.detail
    if (scale > 1 || renderer.composeScale(zoom) == 1) {
      val s = scale * d
      val f = renderer.atlas.scaled(frame, s)
      val lx = ((x - screen.x) / zoom).coerceIn(0, f.w - 1)
      val ly = ((y - screen.y) / zoom).coerceIn(0, f.h - 1)
      return renderer.atlas.scaledPixel(frame, s, lx, ly) ushr 24 != 0
    }
    val f = renderer.atlas[frame]
    val lx = ((x - screen.x) * d / zoom).coerceIn(0, f.w - 1)
    val ly = ((y - screen.y) * d / zoom).coerceIn(0, f.h - 1)
    return renderer.atlas.pixel(frame, lx, ly) ushr 24 != 0
  }

  private fun opaque(p: PlacedPerson, x: Int, y: Int) = opaque(p.personFrame, 1, p.screen, x, y)

  private fun opaque(h: PlacedHelper, x: Int, y: Int) = opaque(h.frame, h.placement.scale, h.screen, x, y)

  fun hitTest(x: Int, y: Int): Hit {
    if (scene.pageCount > 1) {
      if (PagerLayout.prev(width, height).contains(x, y)) return Hit.PageHit(-1)
      if (PagerLayout.next(width, height).contains(x, y)) return Hit.PageHit(1)
    }
    val people = computePeople()
    val helpers = renderer.placedHelpers(scene, zoom, originX(), originY(), phase)
    // the person or helper in front (largest feet y) wins
    val front = people.filter { it.screen.contains(x, y) && opaque(it, x, y) }.map { it.placement.y to Hit.PersonHit(it.placement.tabId) } +
      helpers.filter { it.screen.contains(x, y) && opaque(it, x, y) }.map { it.placement.y to Hit.HelperHit(it.placement.tabId, it.placement.helper.id) }
    front.maxByOrNull { it.first }?.let { return it.second }
    val ox = originX()
    val oy = originY()
    for (d in scene.desks) {
      if (d.occupiedBy != null) continue
      val b = d.bounds
      val r = Rect(ox + b.x * zoom, oy + b.y * zoom, b.w * zoom, b.h * zoom)
      if (!r.contains(x, y)) continue
      val frame = renderer.theme.desk(scene.areas[d.areaIndex].slot.seats[d.seat].orientation)
      val lx = ((x - r.x) / zoom).coerceIn(0, b.w - 1)
      val ly = ((y - r.y) / zoom).coerceIn(0, b.h - 1)
      if (renderer.atlas.pixel(frame, lx, ly) ushr 24 != 0) return Hit.DeskHit(d.worktreePath)
    }
    return Hit.None
  }

  private fun computePeople(): List<PlacedPerson> {
    val ox = originX()
    val oy = originY()
    val z = zoom
    return scene.people.map { p ->
      val o = theme.personOffset(p.state, phase)
      val r = p.bounds
      PlacedPerson(
        p, theme.personFrame(p.person.seed % theme.personVariants, p.state, phase, p.facing), theme.bubbleFrame(p.state, phase),
        Rect(ox + (r.x + o.dx) * z, oy + (r.y + o.dy) * z, r.w * z, r.h * z),
      )
    }
  }

  /** The tooltip text under ([x], [y]): "title · agent · elapsed" (and the helper count), a helper's "name · helper of title", or null. */
  fun tooltipAt(x: Int, y: Int): String? = when (val hit = hitTest(x, y)) {
    is Hit.PersonHit -> scene.people.firstOrNull { it.tabId == hit.tabId }?.let { renderer.tooltip(it, clock()) }
    is Hit.HelperHit -> scene.helpers.firstOrNull { it.tabId == hit.tabId && it.helper.id == hit.helperId }?.let { renderer.tooltip(it) }
    else -> null
  }

  override fun getToolTipText(event: MouseEvent): String? = tooltipAt(event.x, event.y)

  /** The "New Claude / New Codex" menu of an empty desk of [worktreePath]. */
  fun deskMenu(worktreePath: String): JPopupMenu = JPopupMenu().apply {
    for ((label, agent) in DESK_MENU) add(JMenuItem(label).apply { addActionListener { actions.start(worktreePath, agent) } })
  }

  private fun click(x: Int, y: Int, e: MouseEvent?) {
    when (val h = hitTest(x, y)) {
      is Hit.PersonHit -> actions.focus(h.tabId)
      is Hit.HelperHit -> actions.focus(h.tabId)
      is Hit.DeskHit -> if (e != null && !java.awt.GraphicsEnvironment.isHeadless()) deskMenu(h.worktreePath).show(this, x, y)
      is Hit.PageHit -> showPage(h.delta)
      Hit.None -> Unit
    }
  }

  /** A click at ([x], [y]) without a mouse event (the desk menu is not shown): for tests. */
  fun clickAt(x: Int, y: Int) = click(x, y, null)

  // ---- painting

  /** Screen rectangle of the person drawn for [tabId], or null. */
  fun screenRect(tabId: String): java.awt.Rectangle? =
    computePeople().firstOrNull { it.placement.tabId == tabId }?.screen?.let { java.awt.Rectangle(it.x, it.y, it.w, it.h) }

  private fun refreshPeople() {
    val people = computePeople()
    val list = people.map {
      mapOf(
        "tabId" to it.placement.tabId,
        "status" to it.placement.state.key,
        "personFrame" to it.personFrame,
        "bubbleFrame" to it.bubbleFrame,
        "screen" to java.awt.Rectangle(it.screen.x, it.screen.y, it.screen.w, it.screen.h),
      )
    }
    // read-only, for the real-IDE probe: it reads the entries by key without importing plugin classes
    val helpers = renderer.placedHelpers(scene, zoom, originX(), originY(), phase).map {
      mapOf(
        "tabId" to it.placement.tabId,
        "helperId" to it.placement.helper.id,
        "name" to it.placement.helper.name,
        "personFrame" to it.frame,
        "screen" to java.awt.Rectangle(it.screen.x, it.screen.y, it.screen.w, it.screen.h),
      )
    }
    putClientProperty(HELPERS_PROPERTY, java.util.Collections.unmodifiableList(helpers))
    putClientProperty(PEOPLE_PROPERTY, java.util.Collections.unmodifiableList(list))
    last = PaintResult(people, last.tags, last.helpers, last.helperTags)
  }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      renderer.paintBackground(g2, width, height)
      last = renderer.paintScene(g2, scene, zoom, originX(), originY(), phase)
      renderer.drawHeader(g2, floor, 18, 26)
      if (scene.pageCount > 1) renderer.drawPager(g2, width, height, scene.page, scene.pageCount)
    } finally {
      g2.dispose()
    }
  }

  companion object {
    const val MAX_ZOOM = 6

    /** How much of the focus a fitted zoom may crop (shared by both sides), as a fraction of it. */
    const val FIT_SLACK = 0.12

    /** Client property holding the visible people: a read-only list of maps with tabId, status, personFrame, bubbleFrame, screen (java.awt.Rectangle). */
    const val PEOPLE_PROPERTY = "phantom.aiviz.people"

    /** Client property holding the visible helpers: a read-only list of maps with tabId (the parent's), helperId, name, personFrame, screen. */
    const val HELPERS_PROPERTY = "phantom.aiviz.helpers"

    /** Read-only client property: the id of the theme drawn, for the probe. */
    const val THEME_PROPERTY = "phantom.aiviz.theme"

    val DESK_MENU: List<Pair<String, AiAgent>>
      get() = listOf(message("desk.new.claude") to AiAgent.CLAUDE, message("desk.new.codex") to AiAgent.CODEX)

    private fun min(a: Int, b: Int) = if (a < b) a else b
    private fun max(a: Int, b: Int) = if (a > b) a else b
  }
}
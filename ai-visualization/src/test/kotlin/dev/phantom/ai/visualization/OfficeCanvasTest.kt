// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.api.AiAgent
import dev.phantom.ai.api.AiSessionStatus
import dev.phantom.ai.visualization.model.OfficeModel
import dev.phantom.ai.visualization.model.ProjectData
import dev.phantom.ai.visualization.theme.ThemeA
import dev.phantom.ai.visualization.ui.Hit
import dev.phantom.ai.visualization.ui.OfficeActions
import dev.phantom.ai.visualization.ui.OfficeCanvas
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JMenuItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeCanvasTest {
  private val focused = mutableListOf<String>()
  private val started = mutableListOf<Pair<String, AiAgent>>()
  private val actions = object : OfficeActions {
    override fun focus(tabId: String) {
      focused += tabId
    }

    override fun start(worktreePath: String, agent: AiAgent) {
      started += worktreePath to agent
    }
  }

  private fun canvas(worktrees: Int = 3): OfficeCanvas {
    val c = OfficeCanvas(ThemeA, actions) { 100L + 125_000 }
    c.setSize(1200, 800)
    val data = ProjectData(
      "/repo", "repo", (0 until worktrees).map { Fixtures.wt(it, it == 0) },
      AiSessionStatus.values().mapIndexed { i, s -> Fixtures.session("t$i", "/repo/wt0", s, 100L + i) },
    )
    c.setFloor(Fixtures.byWorktree(listOf(data)).floors[0])
    return c
  }

  private fun <T> OfficeCanvas.scan(accept: (Hit) -> T?): T? {
    for (y in 0 until height step 2) for (x in 0 until width step 2) accept(hitTest(x, y))?.let { return it }
    return null
  }

  @Test
  fun personHitsToItsTabAndClickFocusesIt() {
    val c = canvas()
    for (p in c.scene.people) {
      val pt = (0 until c.height).firstNotNullOfOrNull { y ->
        (0 until c.width).firstOrNull { x -> c.hitTest(x, y) == Hit.PersonHit(p.tabId) }?.let { it to y }
      }
      assertNotNull("no point hits ${p.tabId}", pt)
      c.clickAt(pt!!.first, pt.second)
      assertEquals(p.tabId, focused.last())
    }
    assertEquals(c.scene.people.map { it.tabId }, focused)
  }

  @Test
  fun emptyDeskHitsToItsWorktreeAndMenuStartsAnAgent() {
    val c = canvas()
    // wt0 has more people than seats, so its desks are all taken; wt1 and wt2 have empty desks
    assertEquals(null, c.scan { (it as? Hit.DeskHit)?.worktreePath?.takeIf { p -> p == "/repo/wt0" } })
    val path = c.scan { (it as? Hit.DeskHit)?.worktreePath?.takeIf { p -> p == "/repo/wt1" } }
    assertEquals("/repo/wt1", path)
    assertEquals("/repo/wt2", c.scan { (it as? Hit.DeskHit)?.worktreePath?.takeIf { p -> p == "/repo/wt2" } })
    val menu = c.deskMenu(path!!)
    val items = menu.components.filterIsInstance<JMenuItem>()
    assertEquals(listOf("New Claude", "New Codex"), items.map { it.text })
    items[1].doClick()
    items[0].doClick()
    assertEquals(listOf(path to AiAgent.CODEX, path to AiAgent.CLAUDE), started)
  }

  @Test
  fun zoomIsAnIntegerClampedToItsRange() {
    val c = canvas()
    val start = c.zoom
    assertTrue(start in 1..OfficeCanvas.MAX_ZOOM)
    c.zoomBy(1)
    assertEquals(minOf(OfficeCanvas.MAX_ZOOM, start + 1), c.zoom)
    repeat(20) { c.zoomBy(1) }
    assertEquals(OfficeCanvas.MAX_ZOOM, c.zoom)
    repeat(20) { c.zoomBy(-1) }
    assertEquals(1, c.zoom)
    // ctrl+wheel up zooms in by one step, wheel without ctrl does nothing
    c.dispatchEvent(MouseWheelEvent(c, MouseEvent.MOUSE_WHEEL, 0, InputEvent.CTRL_DOWN_MASK, 600, 400, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1))
    assertEquals(2, c.zoom)
    c.dispatchEvent(MouseWheelEvent(c, MouseEvent.MOUSE_WHEEL, 0, 0, 600, 400, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1))
    assertEquals(2, c.zoom)
  }

  @Test
  fun trackpadWheelZoomsByWholeNotchesAndItsTailDoesNotZoomBack() {
    val c = canvas()
    repeat(20) { c.zoomBy(-1) }
    fun wheel(precise: Double) = c.dispatchEvent(
      MouseWheelEvent(c, MouseEvent.MOUSE_WHEEL, 0, InputEvent.META_DOWN_MASK, 600, 400, 600, 400, 0, false,
        MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, precise.toInt(), precise),
    )
    // a trackpad swipe up: small fractions, wheelRotation 0, adding up to two notches
    repeat(8) { wheel(-0.25) }
    assertEquals(3, c.zoom)
    // the swipe's inertia tail: tiny or zero deltas, never a zoom out
    repeat(10) { wheel(-0.01) }
    wheel(0.0)
    assertEquals(3, c.zoom)
    // swiping down zooms out by the notches it adds up to
    repeat(4) { wheel(0.25) }
    assertEquals(2, c.zoom)
  }

  @Test
  fun dragPans() {
    val c = canvas()
    fun ev(id: Int, x: Int, y: Int) = MouseEvent(c, id, 0, 0, x, y, 1, false, MouseEvent.BUTTON1)
    c.dispatchEvent(ev(MouseEvent.MOUSE_PRESSED, 100, 100))
    c.dispatchEvent(ev(MouseEvent.MOUSE_DRAGGED, 140, 130))
    c.dispatchEvent(ev(MouseEvent.MOUSE_RELEASED, 140, 130))
    assertEquals(40, c.panX)
    assertEquals(30, c.panY)
    assertTrue("a drag is not a click", focused.isEmpty() && started.isEmpty())
  }

  @Test
  fun tooltipHasTitleAgentAndElapsed() {
    val c = canvas()
    val p = c.scene.people.first()
    val r = c.screenRect(p.tabId)!!
    val text = (0 until r.height).firstNotNullOfOrNull { dy ->
      (0 until r.width).firstNotNullOfOrNull { dx -> c.tooltipAt(r.x + dx, r.y + dy)?.takeIf { c.hitTest(r.x + dx, r.y + dy) == Hit.PersonHit(p.tabId) } }
    }
    assertNotNull(text)
    assertTrue(text, "title ${p.tabId}" in text!! && "claude" in text && "2m" in text)
    assertEquals(null, c.tooltipAt(1, 1))
  }

  @Test
  fun morePagesThanSixWorktreesPage() {
    val c = canvas(8)
    assertEquals(2, c.scene.pageCount)
    assertEquals(0, c.page)
    val next = dev.phantom.ai.visualization.render.PagerLayout.next(c.width, c.height)
    assertEquals(Hit.PageHit(1), c.hitTest(next.x + 2, next.y + 2))
    c.clickAt(next.x + 2, next.y + 2)
    assertEquals(1, c.page)
    c.showPage(5)
    assertEquals(1, c.page)
  }

  @Test
  fun publishesThePeopleForTheProbe() {
    val c = canvas()
    @Suppress("UNCHECKED_CAST")
    val list = c.getClientProperty("phantom.aiviz.people") as List<Map<String, Any?>>
    assertEquals(4, list.size)
    assertEquals(setOf("working", "waiting", "done", "idle"), list.map { it["status"] }.toSet())
    for (e in list) {
      assertTrue(e["personFrame"] as String, (e["personFrame"] as String).contains("_" + e["status"]))
      assertNotNull(e["bubbleFrame"])
      assertTrue(e["screen"] is java.awt.Rectangle)
    }
    try {
      @Suppress("UNCHECKED_CAST")
      (list as MutableList<Any?>).clear()
      error("list must be read-only")
    } catch (_: UnsupportedOperationException) {
    }
  }
}

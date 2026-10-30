// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.api.AiAgent
import dev.phantom.ai.api.AiSessionStatus
import dev.phantom.ai.visualization.model.OfficeLayout
import dev.phantom.ai.visualization.model.OfficeModel
import dev.phantom.ai.visualization.model.ProjectData
import dev.phantom.ai.visualization.model.VizState
import dev.phantom.ai.visualization.render.OfficeRenderer
import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.ThemeA
import dev.phantom.ai.visualization.ui.Animation
import dev.phantom.ai.visualization.ui.OfficeActions
import dev.phantom.ai.visualization.ui.OfficeCanvas
import java.awt.Rectangle
import javax.swing.JComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationTest {
  private val noActions = object : OfficeActions {
    override fun focus(tabId: String) = Unit
    override fun start(worktreePath: String, agent: AiAgent) = Unit
  }

  private class Switchable : JComponent() {
    var showing = false
    override fun isShowing() = showing
  }

  @Test
  fun timerRunsOnlyWhileShowing() {
    val c = OfficeCanvas(ThemeA, noActions)
    assertFalse("not showing: no timer", c.animation.isRunning)
    c.animation.sync()
    assertFalse(c.animation.isRunning)

    val comp = Switchable()
    var ticks = 0
    val a = Animation(comp, { 4 }, { 250 }) { ticks++ }
    assertFalse(a.isRunning)
    comp.showing = true
    a.sync()
    assertTrue(a.isRunning)
    comp.showing = false
    a.sync()
    assertFalse(a.isRunning)
    comp.showing = true
    a.sync()
    assertTrue(a.isRunning)
    a.dispose()
    assertFalse("disposed: stopped", a.isRunning)
    comp.showing = true
    a.sync()
    assertFalse("disposed stays stopped", a.isRunning)
    assertEquals(0, ticks)
  }

  @Test
  fun phasesCycleThroughTwoToFour() {
    val comp = Switchable()
    val seen = mutableListOf<Int>()
    val a = Animation(comp, { 3 }, { 250 }) { seen += it }
    repeat(7) { a.tick() }
    assertEquals(listOf(1, 2, 0, 1, 2, 0, 1), seen)
    a.dispose()
  }

  @Test
  fun workingSpriteAndWaitingBubbleMoveBetweenPhases() {
    for (n in 0 until ThemeA.animationPhases) {
      val m = (n + 1) % ThemeA.animationPhases
      // the typing person moves: its frame (its arms) or its position changes every phase
      assertTrue(
        "phase $n to $m",
        ThemeA.personOffset(VizState.WORKING, n) != ThemeA.personOffset(VizState.WORKING, m) ||
          ThemeA.personFrame(0, VizState.WORKING, n) != ThemeA.personFrame(0, VizState.WORKING, m),
      )
      assertNotEquals(ThemeA.bubbleOffset(VizState.WAITING, n), ThemeA.bubbleOffset(VizState.WAITING, m))
    }
    assertEquals(ThemeA.personOffset(VizState.DONE, 0), ThemeA.personOffset(VizState.DONE, 1))
  }

  @Test
  fun theDrawnPositionsChangeWithThePhase() {
    val c = OfficeCanvas(ThemeA, noActions)
    c.setSize(1200, 800)
    val data = ProjectData(
      "/repo", "repo", listOf(Fixtures.wt(0, true)),
      listOf(Fixtures.session("w", "/repo/wt0", AiSessionStatus.WORKING), Fixtures.session("a", "/repo/wt0", AiSessionStatus.WAITING, 2000L)),
    )
    c.setFloor(Fixtures.byWorktree(listOf(data)).floors[0])
    @Suppress("UNCHECKED_CAST")
    fun pose() = (c.getClientProperty(OfficeCanvas.PEOPLE_PROPERTY) as List<Map<String, Any?>>).first { it["tabId"] == "w" }
      .let { it["personFrame"] to it["screen"] }
    val before = pose()
    c.animation.tick()
    assertNotEquals("typing moves the working person (its frame or where it is drawn)", before, pose())
    val after = c.screenRect("w")!!
    assertEquals(before.second.let { (it as Rectangle).x }, after.x)

    // the room image differs as the bubble bobs, too
    val atlas = Atlas.of(ThemeA)
    val r = OfficeRenderer(ThemeA, atlas)
    val scene = OfficeLayout(ThemeA, atlas).layout(c.floor!!.areas)
    val p0 = r.nativeImage(scene, 0, 1)
    val p1 = r.nativeImage(scene, 1, 1)
    val b = scene.people.first { it.state == VizState.WAITING }.bubbleBounds
    var diff = 0
    for (y in b.y - 3 until b.y + b.h + 3) for (x in b.x until b.x + b.w) if (p0.getRGB(x, y) != p1.getRGB(x, y)) diff++
    assertTrue("bubble pixels moved: $diff", diff > 10)
    val rect: Rectangle = after
    assertTrue(rect.width > 0)
  }
}

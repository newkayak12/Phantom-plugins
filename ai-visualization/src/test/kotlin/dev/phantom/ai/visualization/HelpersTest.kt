// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.api.AiAgent
import dev.phantom.ai.api.AiSession
import dev.phantom.ai.api.AiSessionStatus
import dev.phantom.ai.api.AiSubagent
import dev.phantom.ai.visualization.model.DeskArea
import dev.phantom.ai.visualization.model.HelperPlacement
import dev.phantom.ai.visualization.model.ItemKind
import dev.phantom.ai.visualization.model.OfficeLayout
import dev.phantom.ai.visualization.model.OfficeModel
import dev.phantom.ai.visualization.model.ProjectData
import dev.phantom.ai.visualization.model.Rect
import dev.phantom.ai.visualization.model.VizState
import dev.phantom.ai.visualization.render.OfficeRenderer
import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.ThemeA
import dev.phantom.ai.visualization.theme.Themes
import dev.phantom.ai.visualization.ui.Hit
import dev.phantom.ai.visualization.ui.OfficeActions
import dev.phantom.ai.visualization.ui.OfficeCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage

class HelpersTest {
  private fun sub(i: Int, running: Boolean = true) = AiSubagent("s$i", listOf("Explore", "Plan", "general-purpose", "Review", "Test", "Docs")[i % 6], running)

  private fun session(tab: String, path: String, status: AiSessionStatus, subs: List<AiSubagent>, at: Long = 100L) =
    AiSession(tab, path, AiAgent.CLAUDE, status, "title $tab", at, subs)

  /** [n] worktrees; worktree 0 has a session per state, each with [subs] running subagents, every other worktree one working session with 2. */
  private fun floor(n: Int, subs: Int) = Fixtures.byWorktree(
    listOf(
      ProjectData(
        "/repo", "repo", (0 until n).map { Fixtures.wt(it, it == 0) },
        AiSessionStatus.values().mapIndexed { i, st -> session("t$i", "/repo/wt0", st, (0 until subs).map { sub(it) }, 100L + i) } +
          (1 until n).map { session("w$it", "/repo/wt$it", AiSessionStatus.WORKING, listOf(sub(0), sub(1))) },
      ),
    ),
  ).floors[0]

  private fun oneSession(status: AiSessionStatus, subs: List<AiSubagent>): List<DeskArea> = Fixtures.byWorktree(
    listOf(ProjectData("/repo", "repo", listOf(Fixtures.wt(0, true)), listOf(session("t0", "/repo/wt0", status, subs)))),
  ).floors[0].areas

  // ---- model

  @Test
  fun runningSubagentsBecomeHelpersOldestFirstAndStoppedOnesAreDropped() {
    val subs = listOf(sub(0), sub(1, running = false), sub(2), sub(3, running = false))
    val p = oneSession(AiSessionStatus.WORKING, subs)[0].people.single()
    assertEquals(listOf("s0" to "Explore", "s2" to "general-purpose"), p.helpers.map { it.id to it.name })
    assertEquals(0, p.moreHelpers)
    assertEquals(2, p.helperCount)
    assertEquals(0, oneSession(AiSessionStatus.WORKING, emptyList())[0].people.single().helperCount)
    assertEquals(0, oneSession(AiSessionStatus.WORKING, listOf(sub(0, running = false)))[0].people.single().helperCount)
  }

  @Test
  fun helpersAreCappedAndTheRestCountAsMore() {
    val p = oneSession(AiSessionStatus.WORKING, (0 until 7).map { sub(it) } + sub(7, running = false))[0].people.single()
    assertEquals(OfficeModel.MAX_HELPERS, p.helpers.size)
    assertEquals(listOf("s0", "s1", "s2", "s3"), p.helpers.map { it.id })
    assertEquals(3, p.moreHelpers)
    assertEquals(7, p.helperCount)
  }

  @Test
  fun floorCountsStayPerSession() {
    val f = floor(2, 3)
    assertEquals(5, f.sessionCount)
    assertEquals(2, f.counts[VizState.WORKING])
  }

  // ---- layout

  @Test
  fun helpersSitOnTheirRugBesideTheParentClearOfPeopleAndFurniture() {
    for (theme in Themes.all) {
      val atlas = Atlas.of(theme)
      val layout = OfficeLayout(theme, atlas)
      for (n in 1..6) for (subs in listOf(1, 2, 4)) {
        val scene = layout.layout(floor(n, subs).areas)
        val ctx = "theme ${theme.id} n=$n subs=$subs"
        // every running subagent is either drawn or in its parent's "+N"
        for (p in scene.people) {
          val drawn = scene.helpers.count { it.tabId == p.tabId }
          assertEquals("$ctx ${p.tabId}", p.person.helperCount, drawn + (scene.helperOverflow[p.tabId] ?: 0))
          // a lone session at its desk has room for both full-size subordinates up to three areas (two where the people are as tall
          // as a desk and its monitor); more areas crowd the room, so some may show "+N" only
          val roomy = if (theme.personDetail > 1) 2 else 3
          if (p.tabId.startsWith("w") && n <= roomy) assertEquals("$ctx ${p.tabId} draws both helpers", 2, drawn)
        }
        val furniture = scene.items.filter { it.layer == 5 && it.kind == ItemKind.SPRITE && it.scale == 1 }
        // an opaque pixel of [frame] (anchored at x, y) on an opaque pixel of the subordinate [h] in any animation phase
        fun covers(frame: String, x: Int, y: Int, h: HelperPlacement): Boolean {
          val f = atlas[frame]
          return (0 until theme.animationPhases).any { ph ->
            val pose = theme.personFrame(h.variant, VizState.WORKING, ph)
            val off = theme.personOffset(VizState.WORKING, ph)
            val pf = atlas.scaled(pose, h.scale * theme.personDetail)
            val r = Rect(h.x + off.dx - pf.anchorX, h.y + off.dy - pf.anchorY, pf.w, pf.h)
            (0 until f.h).any { fy ->
              (0 until f.w).any { fx ->
                val sx = x - f.anchorX + fx
                val sy = y - f.anchorY + fy
                r.contains(sx, sy) && atlas.pixel(frame, fx, fy) ushr 24 != 0 && atlas.scaledPixel(pose, h.scale * theme.personDetail, sx - r.x, sy - r.y) ushr 24 != 0
              }
            }
          }
        }
        for (h in scene.helpers) {
          val rug = scene.areas[h.parent.areaIndex].slot.rug
          assertTrue("$ctx ${h.key} on its rug", h.u >= rug.u && h.v >= rug.v && h.u <= rug.u + rug.w && h.v <= rug.v + rug.d)
          for (p in scene.people) {
            assertFalse("$ctx ${h.key} overlaps ${p.tabId}", h.footprint.intersects(p.bounds))
            assertFalse("$ctx ${h.key} overlaps the bubble of ${p.tabId}", h.footprint.intersects(p.bubbleBounds))
          }
          // nothing in front of it covers it, and it covers none of its own area's desks (its chair may tuck under one) unless the
          // theme lets it stand at them
          for (f in furniture) if (f.key > h.u + h.v) assertFalse("$ctx ${h.key} covered by ${f.frame}", covers(f.frame!!, f.x, f.y, h))
          if (!theme.helperMayCoverOwnDesks) for (d in scene.desks) if (d.areaIndex == h.parent.areaIndex) {
            val frame = theme.desk(scene.areas[d.areaIndex].slot.seats[d.seat].orientation)
            val f = atlas[frame]
            assertFalse("$ctx ${h.key} over its area's desk ${d.seat}", covers(frame, d.bounds.x + f.anchorX, d.bounds.y + f.anchorY, h))
          }
          for (o in scene.helpers) if (o !== h) assertFalse("$ctx ${h.key} overlaps ${o.key}", h.footprint.intersects(o.footprint))
          // beside the parent: within a few person widths
          assertTrue("$ctx ${h.key} near its parent", kotlin.math.abs(h.x - h.parent.x) <= 100 && kotlin.math.abs(h.y - h.parent.y) <= 64)
          // a full-size subordinate: a working person's own frame, unshrunk, with a look of its own
          assertEquals("$ctx ${h.key} full size", 1, h.scale)
          assertEquals("$ctx ${h.key} full size", atlas[theme.person(h.variant, VizState.WORKING)].h / theme.personDetail, h.bounds.h)
          assertTrue("$ctx ${h.key} looks unlike its lead", h.variant != h.parent.person.seed % theme.personVariants)
        }
      }
    }
  }

  @Test
  fun noSubagentsMeansNoHelperItems() {
    val scene = OfficeLayout(ThemeA, Atlas.of(ThemeA)).layout(Fixtures.areas(3))
    assertTrue(scene.helpers.isEmpty())
    assertTrue(scene.helperOverflow.isEmpty())
    assertTrue(scene.items.none { it.kind == ItemKind.HELPER || it.scale != 1 })
  }

  @Test
  fun overTheCapShowsPlusN() {
    val scene = OfficeLayout(ThemeA, Atlas.of(ThemeA)).layout(oneSession(AiSessionStatus.WORKING, (0 until 6).map { sub(it) }))
    assertEquals(4, scene.helpers.size)
    assertEquals(mapOf("t0" to 2), scene.helperOverflow)
    val img = BufferedImage(1440, 900, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    val res = try {
      OfficeRenderer(ThemeA).paintScene(g, scene, 3, 0, 0, 0)
    } finally {
      g.dispose()
    }
    assertEquals(setOf("\ub9ac\uc11c\uce58 \u00b7 Explore", "\uae30\ud68d \u00b7 Plan", "\uac1c\ubc1c \u00b7 general-purpose", "\ub9ac\ubdf0 \u00b7 Review", "+2"), res.helperTags.map { it.text }.toSet())
    assertTrue(res.helperTags.all { it.tabId == "t0" && it.state == VizState.WORKING })
    // helper tags keep clear of the session tags and of each other
    val all = res.tags.map { it.rect } + res.helperTags.map { it.rect }
    for (i in all.indices) for (j in i + 1 until all.size) assertFalse("${all[i]} / ${all[j]}", all[i].intersects(all[j]))
    // and none hides a helper
    for (t in res.helperTags) for (h in res.helpers) assertFalse("${t.text} over ${h.placement.key}", t.rect.intersects(h.screen))
  }

  // ---- render

  @Test
  fun twoSubagentsDrawExtraPixelsNextToTheParentInEveryTheme() {
    for (theme in Themes.all) {
      val atlas = Atlas.of(theme)
      val layout = OfficeLayout(theme, atlas)
      val renderer = OfficeRenderer(theme, atlas)
      for (status in AiSessionStatus.values()) {
        val with = layout.layout(oneSession(status, listOf(sub(0), sub(1))))
        val without = layout.layout(oneSession(status, emptyList()))
        assertEquals(2, with.helpers.size)
        val a = renderer.nativeImage(with, 0, 3)
        val b = renderer.nativeImage(without, 0, 3)
        val parent = with.people.single()
        for (h in with.helpers) {
          val r = h.footprint
          var changed = 0
          for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) {
            if (a.getRGB(x, y) != b.getRGB(x, y) && a.getRGB(x, y) ushr 24 != 0) changed++
          }
          assertTrue("theme ${theme.id} $status ${h.key}: $changed changed pixels", changed >= 20)
          assertTrue("theme ${theme.id} $status ${h.key} next to the parent", kotlin.math.abs(h.x - parent.x) <= 100 && kotlin.math.abs(h.y - parent.y) <= 64)
        }
        // nothing changes away from the helpers (and their stools)
        for (y in 0 until a.height) for (x in 0 until a.width) {
          if (a.getRGB(x, y) != b.getRGB(x, y)) {
            assertTrue("theme ${theme.id} $status: ($x, $y) changed away from the helpers", with.helpers.any { it.footprint.contains(x, y) })
          }
        }
      }
    }
  }

  // ---- canvas

  private class Recorder : OfficeActions {
    val focused = ArrayList<String>()
    override fun focus(tabId: String) {
      focused += tabId
    }
    override fun start(worktreePath: String, agent: AiAgent) = Unit
  }

  @Test
  fun clickingAHelperFocusesItsParentAndHoverNamesBoth() {
    val rec = Recorder()
    val c = OfficeCanvas(ThemeA, rec, clock = { 100L + 5 * 60_000 })
    c.setSize(1440, 900)
    val f = Fixtures.byWorktree(
      listOf(ProjectData("/repo", "repo", listOf(Fixtures.wt(0, true)), listOf(session("t0", "/repo/wt0", AiSessionStatus.WORKING, listOf(sub(0), sub(1)))))),
    ).floors[0]
    c.setFloor(f)
    try {
      @Suppress("UNCHECKED_CAST")
      val helpers = c.getClientProperty(OfficeCanvas.HELPERS_PROPERTY) as List<Map<String, Any?>>
      assertEquals(listOf("Explore", "Plan"), helpers.map { it["name"] })
      val r = helpers.first { it["name"] == "Explore" }["screen"] as java.awt.Rectangle
      val point = (0 until r.height).asSequence().flatMap { dy -> (0 until r.width).asSequence().map { dx -> r.x + dx to r.y + dy } }
        .firstOrNull { (x, y) -> c.hitTest(x, y) == Hit.HelperHit("t0", "s0") }
      assertNotNull("a pixel of the helper hits it", point)
      val (x, y) = point!!
      assertEquals("Explore · helper of title t0", c.tooltipAt(x, y))
      c.clickAt(x, y)
      assertEquals(listOf("t0"), rec.focused)
      // the parent's tooltip counts its helpers
      val pr = c.screenRect("t0")!!
      val tip = (0 until pr.height).asSequence().flatMap { dy -> (0 until pr.width).asSequence().map { dx -> pr.x + dx to pr.y + dy } }
        .firstNotNullOfOrNull { (px, py) -> c.tooltipAt(px, py)?.takeIf { c.hitTest(px, py) == Hit.PersonHit("t0") } }
      assertEquals("title t0 · claude · 5m · 2 helpers", tip)
    } finally {
      c.dispose()
    }
  }
}

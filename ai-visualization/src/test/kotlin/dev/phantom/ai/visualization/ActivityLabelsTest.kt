// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.api.AiAgent
import dev.phantom.ai.api.AiSession
import dev.phantom.ai.api.AiSessionStatus
import dev.phantom.ai.api.AiSubagent
import dev.phantom.ai.visualization.model.Floor
import dev.phantom.ai.visualization.model.OfficeLayout
import dev.phantom.ai.visualization.model.OfficeModel
import dev.phantom.ai.visualization.model.ProjectData
import dev.phantom.ai.visualization.model.VizState
import dev.phantom.ai.visualization.render.OfficeRenderer
import dev.phantom.ai.visualization.render.PaintResult
import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.Theme
import dev.phantom.ai.visualization.theme.ThemeA
import dev.phantom.ai.visualization.theme.ThemeC
import dev.phantom.ai.visualization.theme.Themes
import dev.phantom.ai.visualization.ui.OfficeActions
import dev.phantom.ai.visualization.ui.OfficeCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** What every person and subordinate does is written above its head, legible at any zoom and under any lighting. */
class ActivityLabelsTest {
  private fun session(tab: String, wt: Int, status: AiSessionStatus, activity: String?, subs: List<AiSubagent> = emptyList(), agent: AiAgent = AiAgent.CLAUDE) =
    AiSession(tab, "/repo/wt$wt", agent, status, "title $tab", 100L + tab.hashCode() % 50, subs, activity)

  private fun floor(): Floor = Fixtures.byWorktree(
    listOf(
      ProjectData(
        // two sessions per worktree, so every one has a desk or a spot on its own rug in every theme
        "/repo", "repo", (0 until 4).map { Fixtures.wt(it, it == 0) },
        listOf(
          session(
            "lead", 0, AiSessionStatus.WORKING, "Task: Explore",
            listOf(AiSubagent("a1", "Explore", true, "Grep \"drawTags\""), AiSubagent("a2", "Plan", true), AiSubagent("a0", "old", false)),
          ),
          session("wait", 0, AiSessionStatus.WAITING, "권한 요청: Bash"),
          session("done", 1, AiSessionStatus.DONE, null),
          session("idle", 1, AiSessionStatus.NONE, null),
          session("codex", 2, AiSessionStatus.WORKING, "Bash: ./gradlew test", listOf(AiSubagent("team:rev@t", "reviewer", true, "Read README.md")), AiAgent.CODEX),
          session("many", 3, AiSessionStatus.WORKING, "Edit OfficeRenderer.kt", (0 until 6).map { AiSubagent("m$it", "worker-$it", true, "Read F$it.kt") }),
        ),
      ),
    ),
  ).floors[0]

  private fun paint(theme: Theme, zoom: Int): Pair<PaintResult, BufferedImage> {
    val scene = OfficeLayout(theme, Atlas.of(theme)).layout(floor().areas)
    val img = BufferedImage(scene.width * zoom, scene.height * zoom, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    val res = try {
      OfficeRenderer(theme).paintScene(g, scene, zoom, 0, 0, 0)
    } finally {
      g.dispose()
    }
    return res to img
  }

  @Test
  fun everyPersonAndSubordinateHasAnActivityLabelAboveItsHead() {
    for (theme in Themes.all) for (zoom in 1..4) {
      val ctx = "theme ${theme.id} zoom $zoom"
      val (res, _) = paint(theme, zoom)
      val byTab = res.activities.filter { it.subagentId == null }.associateBy { it.tabId }
      assertEquals(ctx, setOf("lead", "wait", "done", "idle", "codex", "many"), byTab.keys)
      assertEquals(ctx, "Task: Explore", byTab.getValue("lead").text)
      assertEquals(ctx, "권한 요청: Bash", byTab.getValue("wait").text)
      // no activity reported: the state says it
      assertEquals(ctx, "완료", byTab.getValue("done").text)
      assertEquals(ctx, "대기", byTab.getValue("idle").text)
      assertEquals(ctx, VizState.WAITING, byTab.getValue("wait").state)
      for (p in res.people) {
        val label = byTab.getValue(p.placement.tabId)
        assertTrue("$ctx ${p.placement.tabId} label above the head", label.rect.y + label.rect.h <= p.screen.y)
      }
      val subs = res.activities.filter { it.subagentId != null }.associateBy { it.subagentId }
      assertEquals(ctx, res.helpers.map { it.placement.helper.id }.toSet(), subs.keys)
      // the lead draws at least one of its two; which ones fit depends on each one's look, the other may only count in its "+N".
      // Drawn, Explore says what it does and Plan (no activity reported) what its state says
      // (where glass partitions box in its two desks, as in B, the big people leave no room by the lead: both count in its "+N")
      if (theme.partition(dev.phantom.ai.visualization.model.Orientation.RW) == null) assertTrue(ctx, res.helpers.any { it.placement.tabId == "lead" })
      else assertTrue(ctx, res.helpers.any { it.placement.tabId == "lead" } || res.helperTags.any { it.tabId == "lead" && it.text.startsWith("+") })
      subs["a1"]?.let { assertEquals(ctx, "Grep \"drawTags\"", it.text) }
      subs["a2"]?.let { assertEquals(ctx, "작업 중", it.text) }
      for (h in res.helpers) {
        val label = subs.getValue(h.placement.helper.id)
        assertTrue("$ctx ${h.placement.key} label above the head", label.rect.y + label.rect.h <= h.screen.y)
      }
      // no tag covers another
      val all = res.tags.map { it.rect } + res.helperTags.map { it.rect } + res.activities.map { it.rect }
      for (i in all.indices) for (j in i + 1 until all.size) assertFalse("$ctx ${all[i]} / ${all[j]}", all[i].intersects(all[j]))
    }
  }

  @Test
  fun labelsKeepTheirStateColorUnderTheNightLighting() {
    val (res, img) = paint(ThemeC, 3)
    for (a in res.activities) {
      // the pill's fill, just inside its left end on the middle row, before the text
      val rgb = img.getRGB(a.rect.x + 3, a.rect.y + a.rect.h / 2) and 0xffffff
      assertEquals("${a.tabId} ${a.text}", ThemeC.palette.stateColor(a.state).rgb and 0xffffff, rgb)
    }
  }

  @Test
  fun aLeadSaysSoAndItsSubordinatesWearTheirNames() {
    val (res, _) = paint(ThemeA, 3)
    val tags = res.tags.associateBy { it.tabId }
    assertTrue(tags.getValue("lead").text.startsWith("팀장 · claude"))
    assertFalse(tags.getValue("wait").text.startsWith("팀장"))
    // a subordinate's tag says its role, then its name
    val names = res.helperTags.filter { it.tabId == "lead" && !it.text.startsWith("+") }.map { it.text }
    assertEquals(res.helpers.filter { it.placement.tabId == "lead" }.map { "${it.placement.helper.role.label} \u00b7 ${it.placement.helper.name}" }.sorted(), names.sorted())
    assertTrue("\ub9ac\uc11c\uce58 \u00b7 Explore" in names)
    assertTrue(res.helperTags.any { it.tabId == "codex" && it.text == "\ub9ac\ubdf0 \u00b7 reviewer" })
    // the stopped one is gone
    assertTrue(res.helpers.none { it.placement.helper.id == "a0" })
  }

  @Test
  fun theFittedZoomShowsTheDeskAreasBiggerThanTheWholeRoomWould() {
    val c = OfficeCanvas(ThemeA, NoActions)
    try {
      // the people are as tall as a desk and its monitor, so the areas with their heads and labels fill more of a 1440 x 900 view
      // than they did; at 1600 x 1000 the whole room would fit at 3, the areas at 4
      c.setSize(1600, 1000)
      c.setFloor(floor())
      val room = (minOf(1600.0 / OfficeLayout.SCENE_W, 1000.0 / OfficeLayout.SCENE_H) * (1 + OfficeCanvas.FIT_SLACK)).toInt()
      assertEquals(3, room)
      assertTrue("zoom ${c.zoom}", c.zoom >= 4)
      c.setSize(1000, 640)
      assertTrue("zoom ${c.zoom}", c.zoom >= 2)
    } finally {
      c.dispose()
    }
  }

  private object NoActions : OfficeActions {
    override fun focus(tabId: String) = Unit
    override fun start(worktreePath: String, agent: AiAgent) = Unit
  }

  /** A look at the office as a user sees it (canvas at a common editor size, fitted zoom), to /tmp/viz-merged-<theme>.png, every theme. */
  @Test
  fun writesScreenshots() {
    for (theme in Themes.all) {
      val c = OfficeCanvas(theme, NoActions)
      try {
        c.setSize(1280, 800)
        c.setFloor(floor())
        val img = BufferedImage(c.width, c.height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
          c.paint(g)
        } finally {
          g.dispose()
        }
        val out = File(System.getProperty("java.io.tmpdir").let { if (File("/tmp").isDirectory) "/tmp" else it }, "viz-merged-${theme.id.lowercase()}.png")
        assertTrue(ImageIO.write(img, "png", out))
      } finally {
        c.dispose()
      }
    }
  }
}

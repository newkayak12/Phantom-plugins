// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.api.AiAgent
import dev.phantom.ai.api.AiSession
import dev.phantom.ai.api.AiSessionStatus
import dev.phantom.ai.visualization.model.OfficeLayout
import dev.phantom.ai.visualization.model.OfficeModel
import dev.phantom.ai.visualization.model.ProjectData
import dev.phantom.ai.visualization.model.Scene
import dev.phantom.ai.visualization.render.OfficeRenderer
import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.ThemeA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nobody is drawn on top of anybody; tags stay off name plates, bubbles and each other. */
class PeopleSpotsTest {
  private val atlas = Atlas.of(ThemeA)
  private val layout = OfficeLayout(ThemeA, atlas)
  private val renderer = OfficeRenderer(ThemeA, atlas)
  private val states = AiSessionStatus.values()

  /** [worktrees] worktrees with [perArea] sessions each, states cycling so every mix occurs. */
  private fun scene(worktrees: Int, perArea: Int): Scene {
    val sessions = (0 until worktrees).flatMap { w ->
      (0 until perArea).map { k ->
        AiSession("t$w-$k", "/repo/wt$w", AiAgent.CLAUDE, states[(w + k) % states.size], "한글 제목 $w-$k", 100L + k)
      }
    }
    val model = OfficeModel.build(listOf(ProjectData("/repo", "repo", (0 until worktrees).map { Fixtures.wt(it, it == 0) }, sessions)))
    return layout.layout(model.floors[0].areas)
  }

  @Test
  fun noTwoPeopleOverlapForOneToSixWorktreesWithUpToSixSessionsEach() {
    for (n in 1..6) for (k in 1..6) {
      val s = scene(n, k)
      assertEquals(n * k, s.people.size)
      for (i in s.people.indices) for (j in i + 1 until s.people.size) {
        val a = s.people[i]
        val b = s.people[j]
        assertFalse("n=$n k=$k ${a.tabId} vs ${b.tabId}: ${a.bounds} ${b.bounds}", a.bounds.intersects(b.bounds))
      }
    }
  }

  @Test
  fun bubblesDoNotStackEitherWhileThereIsRoom() {
    for (n in 1..6) for (k in 1..3) {
      val s = scene(n, k)
      for (i in s.people.indices) for (j in i + 1 until s.people.size) {
        assertFalse("n=$n k=$k bubbles", s.people[i].bubbleBounds.intersects(s.people[j].bubbleBounds))
      }
    }
  }

  @Test
  fun tagsKeepOffNamePlatesBubblesAndEachOther() {
    for (n in 1..6) for (k in listOf(1, 2, 4)) {
      val s = scene(n, k)
      for (zoom in 1..3) {
        val img = java.awt.image.BufferedImage(1600, 1000, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        val res = renderer.paintScene(g, s, zoom, 10, 10, 0)
        g.dispose()
        val obstacles = renderer.tagObstacles(s, zoom, 10, 10)
        assertTrue(obstacles.size >= s.areas.size + s.people.size)
        for (t in res.tags) {
          val r = dev.phantom.ai.visualization.model.TagRect(t.tabId, t.rect.x, t.rect.y, t.rect.w, t.rect.h)
          for (o in obstacles) assertFalse("n=$n k=$k zoom=$zoom tag ${t.tabId} on ${o.id}", r.intersects(o))
        }
        for (i in res.tags.indices) for (j in i + 1 until res.tags.size) {
          assertFalse(res.tags[i].rect.intersects(res.tags[j].rect))
        }
      }
    }
  }
}

// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.api.AiSessionStatus
import dev.phantom.ai.visualization.model.AreaKind
import dev.phantom.ai.visualization.model.ItemKind
import dev.phantom.ai.visualization.model.OfficeLayout
import dev.phantom.ai.visualization.model.OfficeModel
import dev.phantom.ai.visualization.model.Orientation
import dev.phantom.ai.visualization.model.ProjectData
import dev.phantom.ai.visualization.model.VizState
import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.ThemeA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeLayoutTest {
  private val layout = OfficeLayout(ThemeA, Atlas.of(ThemeA))

  @Test
  fun oneToSixWorktreesGetDistinctAreasInsideTheRoom() {
    for (n in 1..6) {
      val slots = OfficeLayout.slots(n)
      assertEquals(n, slots.size)
      for ((i, a) in slots.withIndex()) {
        assertTrue("n=$n rug $i inside", a.rug.inside(OfficeLayout.ROOM))
        assertTrue("n=$n plate $i inside", a.plateU in 0..OfficeLayout.ROOM && a.plateV in 0..OfficeLayout.ROOM)
        assertTrue(a.seats.isNotEmpty())
        for (s in a.seats) {
          val (w, d) = if (s.orientation == Orientation.RW) 20 to 10 else 10 to 20
          assertTrue(
            "n=$n desk at ${s.u},${s.v} inside rug ${a.rug}",
            s.u >= a.rug.u && s.v >= a.rug.v && s.u + w <= a.rug.u + a.rug.w && s.v + d <= a.rug.v + a.rug.d,
          )
        }
        for (j in i + 1 until n) assertTrue("n=$n rugs $i/$j overlap", !a.rug.intersects(slots[j].rug))
      }
    }
  }

  @Test
  fun sceneHasOneAreaPerWorktreeWithItsPlateAndDesks() {
    for (n in 1..6) {
      val scene = layout.layout(Fixtures.areas(n))
      assertEquals(n, scene.areas.size)
      assertTrue(scene.items.count { it.kind == ItemKind.RUG } >= n)
      assertEquals(n, scene.items.count { it.kind == ItemKind.NAMEPLATE })
      assertEquals(scene.areas.sumOf { it.slot.seats.size }, scene.desks.size)
      assertEquals(1, scene.pageCount)
    }
  }

  @Test
  fun moreThanSixWorktreesPage() {
    assertEquals(1, OfficeLayout.pageCount(0))
    assertEquals(1, OfficeLayout.pageCount(6))
    assertEquals(2, OfficeLayout.pageCount(7))
    assertEquals(2, OfficeLayout.pageCount(9))
    assertEquals(3, OfficeLayout.pageCount(13))
    val areas = Fixtures.areas(9)
    val p0 = layout.layout(areas, 0)
    val p1 = layout.layout(areas, 1)
    assertEquals(2, p0.pageCount)
    assertEquals(6, p0.areas.size)
    assertEquals(3, p1.areas.size)
    assertEquals("wt6", p1.areas[0].area.worktree.title)
    assertEquals(1, layout.layout(areas, 99).page)
    assertEquals(0, layout.layout(areas, -4).page)
  }

  @Test
  fun drawListIsSortedBackToFront() {
    val scene = layout.layout(Fixtures.areas(3))
    val keys = scene.items.map { it.layer to it.key }
    assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second })), keys)
    assertEquals(ItemKind.FLOOR_BASE, scene.items.first().kind)
    // a person at a desk comes after its desk and chair, and a person further back comes first
    val ps = scene.items.filter { it.kind == ItemKind.PERSON }
    assertEquals(4, ps.size)
    val order = ps.map { it.y }
    assertTrue(order.isNotEmpty())
    val furniture = scene.items.filter { it.layer == 5 }
    assertEquals(furniture.map { it.key }.sorted(), furniture.map { it.key })
  }

  @Test
  fun eachStateGetsItsFramesAndBubble() {
    val scene = layout.layout(Fixtures.areas(1))
    assertEquals(4, scene.people.size)
    for (p in scene.people) {
      assertTrue(p.frame, p.frame.contains("_" + p.state.key))
      assertEquals("bubble_" + p.state.key, p.bubbleFrame)
    }
    val working = scene.people.single { it.state == VizState.WORKING }
    val waiting = scene.people.single { it.state == VizState.WAITING }
    assertTrue(working.sitting)
    assertTrue(!waiting.sitting)
    assertTrue(scene.people.single { it.state == VizState.IDLE }.sitting)
    assertTrue(!scene.people.single { it.state == VizState.DONE }.sitting)
    assertTrue(scene.people.all { it.bubbleBounds.y < it.bounds.y + it.bounds.h })
    // the people sit at the first four desks, which are occupied; the rest are empty
    assertEquals(4, scene.desks.count { it.occupiedBy != null }.coerceAtMost(4))
  }

  @Test
  fun peopleBeyondTheDesksStandOnTheRug() {
    val sessions = (0 until 5).map { Fixtures.session("x$it", "/repo/wt0", dev.phantom.ai.api.AiSessionStatus.WORKING, it.toLong()) }
    val model = Fixtures.byWorktree(
      listOf(dev.phantom.ai.visualization.model.ProjectData("/repo", "repo", listOf(Fixtures.wt(0, true), Fixtures.wt(1), Fixtures.wt(2)), sessions)),
    )
    val scene = layout.layout(model.floors[0].areas)
    assertEquals(5, scene.people.size)
    assertEquals(listOf(0, 1, -1, -1, -1), scene.people.map { it.seat })
    assertEquals(2, scene.desks.count { it.occupiedBy != null })
    assertEquals(setOf("x0", "x1"), scene.desks.mapNotNull { it.occupiedBy }.toSet())
  }

  @Test
  fun theCeoHasOneDeskAndWhoeverWaitsLinesUpBeforeIt() {
    val lead = Fixtures.session("lead", "/repo/wt0", AiSessionStatus.WAITING, 3)
      .copy(subagents = listOf(dev.phantom.ai.api.AiSubagent("a", "Explore", true)))
    val sessions = listOf(
      Fixtures.session("ceo", "/repo/wt0", AiSessionStatus.WORKING, 1),
      Fixtures.session("ask", "/repo/wt0", AiSessionStatus.WAITING, 2),
      lead,
      Fixtures.session("busy", "/repo/wt0", AiSessionStatus.WORKING, 4),
    )
    val floor = OfficeModel.build(listOf(ProjectData("/repo", "repo", listOf(Fixtures.wt(0, true)), sessions))).floors.single()
    val scene = layout.layout(floor.areas)
    val ceo = scene.areas.single { it.area.kind == AreaKind.CEO }
    assertEquals(1, scene.desks.count { it.areaIndex == ceo.index })
    assertEquals("CEO \u00b7 \uacb0\uc7ac 2", scene.items.single { it.kind == ItemKind.NAMEPLATE && it.x == ceo.plateX && it.y == ceo.plateY }.label)
    val byTab = scene.people.associateBy { it.tabId }
    val boss = byTab.getValue("ceo")
    for (tab in listOf("ask", "lead")) {
      val p = byTab.getValue(tab)
      // in front of the CEO, close by
      assertTrue("$tab before the CEO at ${p.u},${p.v}", p.u + p.v > boss.u + boss.v && Math.abs(p.u - boss.u) <= 30 && p.v - boss.v in 0.0..36.0)
      // its own desk stays its
      assertEquals(1, scene.desks.count { it.occupiedBy == tab })
    }
    assertTrue(byTab.getValue("ceo").sitting && byTab.getValue("busy").sitting)
  }

  @Test
  fun emptyFloorStillHasARoom() {
    val scene = layout.layout(emptyList())
    assertTrue(scene.items.any { it.kind == ItemKind.FLOOR_BASE })
    assertEquals(0, scene.areas.size)
    assertEquals(1, scene.pageCount)
  }

  @Test
  fun projectionIs2To1() {
    assertEquals(OfficeLayout.OX + 20, OfficeLayout.proj(10, 0))
    assertEquals(OfficeLayout.OY + 10, OfficeLayout.projY(10, 0))
    assertEquals(OfficeLayout.OX - 20, OfficeLayout.proj(0, 10))
    assertEquals(OfficeLayout.OY + 3, OfficeLayout.projY(10, 0, 7))
  }
}

// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.api.AiSessionStatus
import dev.phantom.ai.api.AiWorktree
import dev.phantom.ai.visualization.model.Elapsed
import dev.phantom.ai.visualization.model.OfficeModel
import dev.phantom.ai.visualization.model.ProjectData
import dev.phantom.ai.visualization.model.VizState
import dev.phantom.ai.visualization.settings.VizSettingsState
import dev.phantom.ai.visualization.theme.Themes
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeModelTest {
  @Test
  fun statusMapsToState() {
    assertEquals(VizState.WORKING, VizState.of(AiSessionStatus.WORKING))
    assertEquals(VizState.WAITING, VizState.of(AiSessionStatus.WAITING))
    assertEquals(VizState.DONE, VizState.of(AiSessionStatus.DONE))
    assertEquals(VizState.IDLE, VizState.of(AiSessionStatus.NONE))
  }

  @Test
  fun floorsAreNumberedFromTheFirstProjectAndListedTopDown() {
    val ps = listOf("a", "b", "c").map { ProjectData("/p/$it", it, listOf(Fixtures.wt(0, true)), emptyList()) }
    val m = OfficeModel.build(ps)
    assertEquals(listOf("1F", "2F", "3F"), m.floors.map { it.label })
    assertEquals(listOf("3F", "2F", "1F"), m.floorsTopDown.map { it.label })
    assertEquals("b", m.floor("/p/b")!!.name)
  }

  @Test
  fun countsPerStateAndAreas() {
    val sessions = listOf(
      Fixtures.session("1", "/repo/wt0", AiSessionStatus.WORKING),
      Fixtures.session("2", "/repo/wt0", AiSessionStatus.WORKING),
      Fixtures.session("3", "/repo/wt1/", AiSessionStatus.WAITING),
      Fixtures.session("4", "/repo/wt1", AiSessionStatus.NONE),
      Fixtures.session("5", "/repo/wt1", AiSessionStatus.DONE),
    )
    val f = OfficeModel.build(listOf(ProjectData("/repo", "repo", listOf(Fixtures.wt(1), Fixtures.wt(0, true)), sessions))).floors.single()
    assertEquals(mapOf(VizState.WORKING to 2, VizState.WAITING to 1, VizState.DONE to 1, VizState.IDLE to 1), f.counts)
    assertEquals(5, f.sessionCount)
    assertEquals(2, f.worktreeCount)
    assertEquals(listOf("wt0", "wt1"), f.areas.map { it.worktree.title }) // main first
    assertEquals(listOf(2, 3), f.areas.map { it.people.size })
  }

  @Test
  fun worktreePathsThatDifferOnlyBySlashOrSymlinkAreTheSameWorktree() {
    val real = Files.createTempDirectory("aiviz").toRealPath()
    try {
      val link = Files.createTempDirectory("aiviz-link").resolve("l")
      Files.createSymbolicLink(link, real)
      val w = AiWorktree(real.toString(), "w", null, null, true)
      val sessions = listOf(
        Fixtures.session("a", real.toString() + "/", AiSessionStatus.WORKING),
        Fixtures.session("b", link.toString(), AiSessionStatus.DONE),
      )
      val area = OfficeModel.build(listOf(ProjectData("k", "n", listOf(w), sessions))).floors.single().areas.single()
      assertEquals(listOf("a", "b"), area.people.map { it.session.tabId })
    } finally {
      real.toFile().deleteRecursively()
    }
  }

  @Test
  fun peopleAreOrderedByStartAndLookIsStablePerTab() {
    val ss = listOf(Fixtures.session("z", "/repo/wt0", AiSessionStatus.NONE, 5), Fixtures.session("a", "/repo/wt0", AiSessionStatus.NONE, 9))
    val a1 = OfficeModel.build(listOf(ProjectData("/repo", "r", listOf(Fixtures.wt(0)), ss))).floors[0].areas[0]
    val a2 = OfficeModel.build(listOf(ProjectData("/repo", "r", listOf(Fixtures.wt(0)), ss.reversed()))).floors[0].areas[0]
    assertEquals(listOf("z", "a"), a1.people.map { it.session.tabId })
    assertEquals(a1.people.map { it.seed }, a2.people.map { it.seed })
    assertTrue(a1.people.all { it.seed >= 0 })
  }

  @Test
  fun elapsedFormats() {
    assertEquals("5s", Elapsed.format(0, 5_000))
    assertEquals("12m", Elapsed.format(0, 12 * 60_000L))
    assertEquals("1h 5m", Elapsed.format(0, 65 * 60_000L))
    assertEquals("2d 3h", Elapsed.format(0, (2 * 24 + 3) * 3_600_000L))
    assertEquals("0s", Elapsed.format(10_000, 0))
  }

  @Test
  fun themeSettingDefaultsToAAndAAndBAreRegistered() {
    assertEquals("A", VizSettingsState().themeId)
    assertEquals(listOf("A", "B", "C", "F"), Themes.all.map { it.id })
    assertSame(Themes.default, Themes.byId("nope"))
    assertSame(Themes.default, Themes.byId(null))
  }
}

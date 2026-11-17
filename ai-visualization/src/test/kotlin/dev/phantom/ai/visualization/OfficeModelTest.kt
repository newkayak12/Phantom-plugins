// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.api.AiSessionStatus
import dev.phantom.ai.api.AiSubagent
import dev.phantom.ai.api.AiWorktree
import dev.phantom.ai.visualization.model.AreaKind
import dev.phantom.ai.visualization.model.Elapsed
import dev.phantom.ai.visualization.model.OfficeModel
import dev.phantom.ai.visualization.model.PathKey
import dev.phantom.ai.visualization.model.ProjectData
import dev.phantom.ai.visualization.model.Role
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
  fun aFloorPerWorktreeTheMainOneFirstProjectsInOpenOrder() {
    val ps = listOf("a", "b").map { ProjectData("/p/$it", it, listOf(AiWorktree("/p/$it/x", "x", null, 7, false), AiWorktree("/p/$it", it, null, null, true)), emptyList()) }
    val m = OfficeModel.build(ps)
    assertEquals(listOf("1F", "2F", "1F", "2F"), m.floors.map { it.label })
    assertEquals(listOf("a", "x #7", "b", "x #7"), m.floors.map { it.name })
    assertEquals(listOf("/p/a", "/p/a", "/p/b", "/p/b"), m.floors.map { it.projectKey })
    assertEquals("b", m.floor(PathKey.of("/p/b"))!!.name)
    assertEquals(m.floors.reversed(), m.floorsTopDown)
  }

  @Test
  fun theFirstSessionOpenedIsTheCeoLeadsGetATeamTheRestTheOpenDesks() {
    val ws = listOf(Fixtures.wt(1), Fixtures.wt(0, true))
    val lead = Fixtures.session("lead", "/repo/wt0", AiSessionStatus.WORKING, 300).copy(subagents = listOf(AiSubagent("r", "reviewer", true), AiSubagent("e", "Explore", true)))
    val sessions = listOf(
      Fixtures.session("2", "/repo/wt0", AiSessionStatus.WAITING, 200),
      Fixtures.session("1", "/repo/wt0/", AiSessionStatus.WORKING, 100),
      lead,
      Fixtures.session("4", "/repo/wt1", AiSessionStatus.DONE, 50),
      Fixtures.session("stray", "/elsewhere", AiSessionStatus.NONE, 400),
    )
    val (main, other) = OfficeModel.build(listOf(ProjectData("/repo", "repo", ws, sessions))).floors
    assertEquals(listOf(AreaKind.CEO, AreaKind.TEAM, AreaKind.OPEN), main.areas.map { it.kind })
    assertEquals(listOf(listOf("1"), listOf("lead"), listOf("2", "stray")), main.areas.map { a -> a.people.map { it.session.tabId } })
    assertTrue(main.areas[0].people.single().ceo)
    assertTrue(main.areas.drop(1).flatMap { it.people }.none { it.ceo })
    assertEquals(listOf(OfficeModel.CEO_LABEL, "\ud300 \u00b7 title lead", OfficeModel.OPEN_LABEL), main.areas.map { it.label })
    // its subordinates sit by role
    assertEquals(listOf(Role.RESEARCH, Role.REVIEW), main.areas[1].people.single().helpers.map { it.role })
    assertEquals(mapOf(VizState.WORKING to 2, VizState.WAITING to 1, VizState.DONE to 0, VizState.IDLE to 1), main.counts)
    // a worktree with one session: its CEO, and the open desks, empty
    assertEquals(listOf(listOf("4"), emptyList()), other.areas.map { a -> a.people.map { it.session.tabId } })
    assertEquals(1, other.sessionCount)
  }

  @Test
  fun aFloorWithNoSessionIsOpenDesksOnly() {
    val f = OfficeModel.build(listOf(ProjectData("/repo", "repo", listOf(Fixtures.wt(0, true)), emptyList()))).floors.single()
    assertEquals(listOf(AreaKind.OPEN), f.areas.map { it.kind })
    assertTrue(f.areas.single().people.isEmpty())
  }

  @Test
  fun rolesComeFromTheAgentName() {
    assertEquals(Role.RESEARCH, Role.of("Explore"))
    assertEquals(Role.PLAN, Role.of("Plan"))
    assertEquals(Role.REVIEW, Role.of("code-reviewer"))
    assertEquals(Role.TEST, Role.of("test-runner"))
    assertEquals(Role.BUILD, Role.of("general-purpose"))
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
        Fixtures.session("b", link.toString(), AiSessionStatus.DONE, 2000),
      )
      val f = OfficeModel.build(listOf(ProjectData("k", "n", listOf(w), sessions))).floors.single()
      assertEquals(listOf("a", "b"), f.areas.flatMap { it.people }.map { it.session.tabId })
    } finally {
      real.toFile().deleteRecursively()
    }
  }

  @Test
  fun peopleAreOrderedByStartAndLookIsStablePerTab() {
    val ss = listOf(Fixtures.session("z", "/repo/wt0", AiSessionStatus.NONE, 5), Fixtures.session("a", "/repo/wt0", AiSessionStatus.NONE, 9))
    val a1 = OfficeModel.build(listOf(ProjectData("/repo", "r", listOf(Fixtures.wt(0)), ss))).floors[0].areas.flatMap { it.people }
    val a2 = OfficeModel.build(listOf(ProjectData("/repo", "r", listOf(Fixtures.wt(0)), ss.reversed()))).floors[0].areas.flatMap { it.people }
    assertEquals(listOf("z", "a"), a1.map { it.session.tabId })
    assertEquals(a1.map { it.seed }, a2.map { it.seed })
    assertTrue(a1.all { it.seed >= 0 })
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
    assertEquals(listOf("A", "B", "C", "D", "E", "F"), Themes.all.map { it.id })
    assertSame(Themes.default, Themes.byId("nope"))
    assertSame(Themes.default, Themes.byId(null))
  }
}

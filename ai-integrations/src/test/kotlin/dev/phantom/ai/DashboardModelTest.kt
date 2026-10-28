// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.GroupTree
import dev.phantom.ai.core.Repo
import dev.phantom.ai.core.Subagent
import dev.phantom.ai.dashboard.DashboardColumn
import dev.phantom.ai.dashboard.DashboardModel
import dev.phantom.ai.dashboard.DashboardQuery
import dev.phantom.ai.hooks.HookEvent
import dev.phantom.ai.hooks.HookEventMapper
import dev.phantom.ai.hooks.SubagentEvent
import dev.phantom.ai.sidebar.PrInfo
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.SidebarModel
import dev.phantom.ai.sidebar.Worktree
import dev.phantom.ai.sidebar.WorktreeSource
import junit.framework.TestCase

class DashboardModelTest : TestCase() {
  private val source = WorktreeSource { repo ->
    when (repo) {
      "/r/app" -> listOf(Worktree("/r/app", "main", true), Worktree("/w/app-feat", "feat/x", false))
      else -> listOf(Worktree("/r/lib", "main", true))
    }
  }
  private val tree = GroupTree.normalize(emptyList(), listOf(Repo("/r/app", null, 0), Repo("/r/lib", null, 1)))
  private val noPr = PrTitles { _, _ -> null }

  private val sessions = listOf(
    AgentSession("a", "/w/app-feat", AgentKind.CLAUDE, AgentStatus.WORKING, startedAt = 10_000, lastPrompt = "fix it"),
    AgentSession("b", "/w/app-feat", AgentKind.CODEX, AgentStatus.WAITING, startedAt = 20_000),
    AgentSession("c", "/r/app", AgentKind.CLAUDE, AgentStatus.DONE, startedAt = 30_000, unread = true),
    AgentSession("d", "/r/lib", AgentKind.CLAUDE, AgentStatus.DONE, startedAt = 40_000),
    AgentSession("e", "/r/lib", AgentKind.CODEX, startedAt = 5_000),
  )

  private fun model(pr: PrTitles = noPr, q: DashboardQuery = DashboardQuery(), list: List<AgentSession> = sessions) =
    DashboardModel.of(SidebarModel.build(tree, source, list, emptyList(), pr, { null }, 100_000), q)

  fun testColumnsCountsAndNewestFirst() {
    val m = model()
    assertEquals(listOf(AgentStatus.WAITING, AgentStatus.WORKING, AgentStatus.DONE), m.columns.map { it.status })
    assertEquals(listOf("b"), m.column(AgentStatus.WAITING).cards.map { it.tabId })
    // a launched session (no event yet) counts as working; newest first
    assertEquals(listOf("a", "e"), m.column(AgentStatus.WORKING).cards.map { it.tabId })
    assertEquals(listOf("d", "c"), m.column(AgentStatus.DONE).cards.map { it.tabId })
    assertEquals(5, m.total)
    assertEquals(2, m.column(AgentStatus.DONE).count)
  }

  fun testEmptyColumnSaysNone() {
    val m = model(list = listOf(sessions[0]))
    assertEquals("None", m.column(AgentStatus.WAITING).emptyText)
    assertEquals("None", DashboardColumn.EMPTY)
    assertNull(m.column(AgentStatus.WORKING).emptyText)
    assertEquals(1, m.total)
  }

  fun testSeveralSessionsOfOneWorktreeAreSeparateCards() {
    val cards = model().columns.flatMap { it.cards }.filter { it.worktree == "/w/app-feat" }
    assertEquals(setOf("a", "b"), cards.map { it.tabId }.toSet())
  }

  fun testSearchOverWorktreeProjectAndAgent() {
    assertEquals(setOf("a", "b"), ids(DashboardQuery("feat/x")))
    assertEquals(setOf("d", "e"), ids(DashboardQuery("LIB")))
    assertEquals(setOf("b", "e"), ids(DashboardQuery("codex")))
    assertEquals(emptySet<String>(), ids(DashboardQuery("nothing-matches")))
  }

  fun testAgentAndProjectFilters() {
    assertEquals(setOf("b", "e"), ids(DashboardQuery(agent = AgentKind.CODEX)))
    assertEquals(setOf("d", "e"), ids(DashboardQuery(projectPath = "/r/lib")))
    assertEquals(setOf("e"), ids(DashboardQuery(agent = AgentKind.CODEX, projectPath = "/r/lib")))
    assertEquals(listOf("app" to "/r/app", "lib" to "/r/lib"), model().projects)
  }

  fun testPrBadgeNumberFromPrTitles() {
    val pr = object : PrTitles {
      override fun titleFor(repoPath: String, branch: String): String? = "Add X"
      override fun infoFor(repoPath: String, branch: String) = if (branch == "feat/x") PrInfo(42, "Add X") else null
    }
    val cards = model(pr).columns.flatMap { it.cards }
    assertEquals(42, cards.first { it.tabId == "a" }.prNumber)
    assertEquals("feat/x", cards.first { it.tabId == "a" }.branch)
    assertNull(cards.first { it.tabId == "c" }.prNumber)
    assertNull(model().columns.flatMap { it.cards }.first { it.tabId == "a" }.prNumber)
  }

  fun testSavedSessionsAreNotCards() {
    val saved = AgentSession("saved:s1", "/r/app", AgentKind.CLAUDE, sessionId = "s1", startedAt = 0)
    assertEquals(0, model(list = listOf(saved)).total)
  }

  fun testCardCarriesPromptAnswerSubagentsAndUnread() {
    val s = AgentSession(
      "x", "/r/app", AgentKind.CLAUDE, AgentStatus.DONE, unread = true, lastPrompt = "p", lastAnswer = "ans",
      subagents = listOf(Subagent("1", "explore", running = false)), startedAt = 1_000,
    )
    val c = model(list = listOf(s)).columns.flatMap { it.cards }.single()
    assertEquals("p", c.lastPrompt)
    assertEquals("ans", c.lastAnswer)
    assertEquals(listOf("explore"), c.subagents.map { it.name })
    assertTrue(c.unread)
    assertEquals("app", c.project)
    assertEquals("main", c.branch)
    assertEquals("1m", c.elapsed?.let { if (it == "1m") it else it })
  }

  fun testSubagentEventsMapIdAndName() {
    val e = HookEventMapper.map(AgentKind.CLAUDE, """{"hook_event_name":"SubagentStart","agent_id":"ag1","agent_type":"Explore"}""")!!
    assertEquals(SubagentEvent.START, e.subagent)
    assertEquals("ag1", e.subagentId)
    assertEquals("Explore", e.subagentName)
    assertNull(HookEventMapper.map(AgentKind.CLAUDE, """{"hook_event_name":"Stop"}""")!!.subagentId)
    assertTrue(HookEvent("x", null, null, null, null).subagentName == null)
  }

  private fun ids(q: DashboardQuery) = model(q = q).columns.flatMap { it.cards }.map { it.tabId }.toSet()
}

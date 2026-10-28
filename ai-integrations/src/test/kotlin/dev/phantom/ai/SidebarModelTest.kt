// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.Group
import dev.phantom.ai.core.GroupTree
import dev.phantom.ai.core.Repo
import dev.phantom.ai.sidebar.GroupEdits
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.SidebarModel
import dev.phantom.ai.sidebar.Worktree
import dev.phantom.ai.sidebar.WorktreePorcelain
import dev.phantom.ai.sidebar.WorktreeSource
import junit.framework.TestCase

class SidebarModelTest : TestCase() {
  private val source = WorktreeSource { repo ->
    if (repo == "/r/app") listOf(Worktree("/r/app", "main", true), Worktree("/w/app-feat", "feat/x", false)) else emptyList()
  }
  private val tree = GroupTree.normalize(
    listOf(Group("g1", "Work", null, 0), Group("g2", "Sub", "g1", 0), Group("g3", "Empty", null, 1)),
    listOf(Repo("/r/app", "g2", 0), Repo("/r/loose", null, 0)),
  )

  private fun build(sessions: List<AgentSession> = emptyList(), pr: PrTitles = PrTitles { _, _ -> null }) =
    SidebarModel.build(tree, source, sessions, emptyList(), pr, { null }, 100_000)

  fun testNestedGroupsEmptyGroupAndUngroupedRepo() {
    val m = build()
    assertEquals(listOf("Work", "Empty"), m.groups.map { it.group.name })
    assertEquals(listOf("Sub"), m.groups[0].groups.map { it.group.name })
    assertTrue(m.groups[1].groups.isEmpty() && m.groups[1].repos.isEmpty())
    assertEquals(listOf("app"), m.groups[0].groups[0].repos.map { it.name })
    assertEquals(listOf("loose"), m.ungrouped.map { it.name })
    // a repo git lists nothing for still shows its own path as a worktree
    assertEquals(listOf("/r/loose"), m.ungrouped[0].worktrees.map { it.path })
  }

  fun testSeveralSessionsPerWorktreeAndNoEventSessionIsLaunched() {
    val a = AgentSession("a", "/w/app-feat", AgentKind.CLAUDE, status = AgentStatus.WORKING, firstPrompt = "do it", startedAt = 10_000)
    val b = AgentSession("b", "/w/app-feat", AgentKind.CODEX, startedAt = 20_000)
    val c = AgentSession("c", "/r/app", AgentKind.CLAUDE, startedAt = 30_000)
    val wts = build(listOf(a, b, c)).groups[0].groups[0].repos[0].worktrees
    val feat = wts.single { it.branch == "feat/x" }
    assertEquals(listOf("b", "a"), feat.sessions.map { it.session.tabId })
    assertEquals("launched", feat.sessions[0].statusKey)
    assertEquals("working", feat.sessions[1].statusKey)
    assertEquals(listOf("c"), wts.single { it.isMain }.sessions.map { it.session.tabId })
  }

  fun testPrTitleIsUsedWhenGiven() {
    val wts = build(pr = PrTitles { _, branch -> if (branch == "feat/x") "Add X" else null }).groups[0].groups[0].repos[0].worktrees
    assertEquals("Add X", wts.single { it.branch == "feat/x" }.title)
    assertEquals("main", wts.single { it.isMain }.title)
  }

  fun testPrNumberIsExposed() {
    val wts = build(pr = object : PrTitles {
      override fun titleFor(repoPath: String, branch: String): String? = null
      override fun infoFor(repoPath: String, branch: String) = if (branch == "feat/x") dev.phantom.ai.sidebar.PrInfo(12, "Add X") else null
    }).groups[0].groups[0].repos[0].worktrees
    assertEquals(12, wts.single { it.branch == "feat/x" }.prNumber)
    assertEquals("Add X", wts.single { it.branch == "feat/x" }.title)
    assertNull(wts.single { it.isMain }.prNumber)
  }

  fun testRememberedSessionWithoutLiveTabShowsOnceAndLiveWins() {
    val saved = AgentSession("saved:s1", "/r/app", AgentKind.CLAUDE, sessionId = "s1", firstPrompt = "old", startedAt = 0)
    val live = AgentSession("t1", "/r/app", AgentKind.CLAUDE, sessionId = "s1", startedAt = 5_000)
    val m = SidebarModel.build(tree, source, listOf(live), listOf(saved), { _, _ -> null }, { null }, 100_000)
    assertEquals(listOf("t1"), m.groups[0].groups[0].repos[0].worktrees.single { it.isMain }.sessions.map { it.session.tabId })
    val m2 = SidebarModel.build(tree, source, emptyList(), listOf(saved), { _, _ -> null }, { null }, 100_000)
    val row = m2.groups[0].groups[0].repos[0].worktrees.single { it.isMain }.sessions.single()
    assertEquals("old", row.title)
    assertNull(row.elapsed)
  }

  fun testPorcelainParserListsMainLinkedAndDetached() {
    val out = WorktreePorcelain.parse(
      listOf("worktree /r/app", "HEAD abc", "branch refs/heads/main", "", "worktree /w/x", "HEAD def", "detached", "", "worktree /w/y", "branch refs/heads/a/b", ""),
    )
    assertEquals(listOf("/r/app" to "main", "/w/x" to null, "/w/y" to "a/b"), out.map { it.path to it.branch })
    assertEquals(listOf(true, false, false), out.map { it.isMain })
  }

  fun testGroupEditsNestReorderDeleteAndCycle() {
    var t = GroupEdits.addGroup(GroupTree(emptyList(), emptyList()), "A", null, "a")
    t = GroupEdits.addGroup(t, "B", null, "b")
    t = GroupEdits.moveGroup(t, "b", -1)
    assertEquals(listOf("b", "a"), t.childrenOf(null).map { it.id })
    t = GroupEdits.nest(t, "a", "b")
    assertEquals(listOf("a"), t.childrenOf("b").map { it.id })
    t = GroupEdits.addRepo(t, "/r/x", "a")
    t = GroupEdits.delete(t, "a")
    assertEquals("b", t.repos.single().groupId)
    t = GroupEdits.nest(t, "b", "b")
    assertNull(t.groups.single().parentId)
    assertEquals("B2", GroupEdits.rename(t, "b", " B2 ").groups.single().name)
  }
}

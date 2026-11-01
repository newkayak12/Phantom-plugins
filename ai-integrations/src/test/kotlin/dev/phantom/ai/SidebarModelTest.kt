// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.sidebar.NestedWorktree
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.PromptPropagation
import dev.phantom.ai.sidebar.PromptTarget
import dev.phantom.ai.sidebar.SidebarModel
import dev.phantom.ai.sidebar.Worktree
import dev.phantom.ai.sidebar.WorktreeNode
import dev.phantom.ai.sidebar.WorktreePorcelain
import dev.phantom.ai.sidebar.WorktreeSource
import dev.phantom.ai.terminal.submit
import junit.framework.TestCase

class SidebarModelTest : TestCase() {
  private val source = WorktreeSource { repo ->
    if (repo == "/r/app") listOf(Worktree("/r/app", "main", true), Worktree("/w/app-feat", "feat/x", false)) else emptyList()
  }
  private val roots = listOf("/r/app", "/r/loose")

  private fun build(sessions: List<AgentSession> = emptyList(), pr: PrTitles = PrTitles { _, _ -> null }) =
    SidebarModel.build(roots, source, sessions, emptyList(), pr, { null }, 100_000)

  fun testSeveralReposGiveRepoRowsAndOneRepoIsFlat() {
    val m = build()
    assertEquals(listOf("app", "loose"), m.repos.map { it.name })
    assertFalse(m.flat)
    // a repo git lists nothing for still shows its own path as a worktree
    assertEquals(listOf("/r/loose"), m.repos[1].worktrees.map { it.path })
    val one = SidebarModel.build(listOf("/r/app"), source, emptyList(), emptyList(), { _, _ -> null }, { null }, 100_000)
    assertTrue(one.flat)
    assertEquals(listOf("/r/app", "/w/app-feat"), one.repos.single().worktrees.map { it.path })
    assertTrue(SidebarModel.build(emptyList(), source, emptyList(), emptyList(), { _, _ -> null }, { null }, 1).repos.isEmpty())
  }

  fun testSeveralSessionsPerWorktreeAndNoEventSessionIsLaunched() {
    val a = AgentSession("a", "/w/app-feat", AgentKind.CLAUDE, status = AgentStatus.WORKING, firstPrompt = "do it", startedAt = 10_000)
    val b = AgentSession("b", "/w/app-feat", AgentKind.CODEX, startedAt = 20_000)
    val c = AgentSession("c", "/r/app", AgentKind.CLAUDE, startedAt = 30_000)
    val wts = build(listOf(a, b, c)).repos[0].worktrees
    val feat = wts.single { it.branch == "feat/x" }
    assertEquals(listOf("b", "a"), feat.sessions.map { it.session.tabId })
    assertEquals("launched", feat.sessions[0].statusKey)
    assertEquals("working", feat.sessions[1].statusKey)
    assertEquals(listOf("c"), wts.single { it.isMain }.sessions.map { it.session.tabId })
  }

  fun testPrTitleIsUsedWhenGiven() {
    val wts = build(pr = PrTitles { _, branch -> if (branch == "feat/x") "Add X" else null }).repos[0].worktrees
    assertEquals("Add X", wts.single { it.branch == "feat/x" }.title)
    assertEquals("main", wts.single { it.isMain }.title)
  }

  fun testPrNumberIsExposed() {
    val wts = build(pr = object : PrTitles {
      override fun titleFor(repoPath: String, branch: String): String? = null
      override fun infoFor(repoPath: String, branch: String) = if (branch == "feat/x") dev.phantom.ai.sidebar.PrInfo(12, "Add X") else null
    }).repos[0].worktrees
    assertEquals(12, wts.single { it.branch == "feat/x" }.prNumber)
    assertEquals("Add X", wts.single { it.branch == "feat/x" }.title)
    assertNull(wts.single { it.isMain }.prNumber)
  }

  fun testRememberedSessionWithoutLiveTabShowsOnceAndLiveWins() {
    val saved = AgentSession("saved:s1", "/r/app", AgentKind.CLAUDE, sessionId = "s1", firstPrompt = "old", startedAt = 0)
    val live = AgentSession("t1", "/r/app", AgentKind.CLAUDE, sessionId = "s1", startedAt = 5_000)
    val m = SidebarModel.build(roots, source, listOf(live), listOf(saved), { _, _ -> null }, { null }, 100_000)
    assertEquals(listOf("t1"), m.repos[0].worktrees.single { it.isMain }.sessions.map { it.session.tabId })
    val m2 = SidebarModel.build(roots, source, emptyList(), listOf(saved), { _, _ -> null }, { null }, 100_000)
    val row = m2.repos[0].worktrees.single { it.isMain }.sessions.single()
    assertEquals("old", row.title)
    assertNull(row.elapsed)
  }

  fun testWorktreesNestUnderTheWorktreeTheyWereMadeFromNotTheirBranch() {
    val src = WorktreeSource {
      listOf(
        Worktree("/r/app", "main", true), Worktree("/w/310", "refactor/CL-310", false),
        Worktree("/w/531", "refactor/CL-531", false), Worktree("/w/526", "refactor/CL-526", false),
        Worktree("/w/9", "feat/9", false),
      )
    }
    val parents = mapOf("/w/531" to "/w/310", "/w/526" to "/w/310/", "/w/9" to "/w/gone", "/r/app" to "/w/310")
    val m = SidebarModel.build(listOf("/r/app"), src, emptyList(), emptyList(), { _, _ -> null }, { null }, 1, { parents[it] })
    val nested = SidebarModel.nest(m.repos.single().worktrees)
    // the main worktree never nests, and a parent that is gone puts the worktree at the top
    assertEquals(listOf("/r/app", "/w/310", "/w/9"), nested.map { it.node.path })
    assertEquals(listOf("/w/531", "/w/526"), nested[1].children.map { it.node.path })
  }

  fun testWorktreesInACycleGoToTheTop() {
    fun wt(p: String, parent: String?) = WorktreeNode(p, p, null, false, emptyList(), parentPath = parent)
    val nested = SidebarModel.nest(listOf(wt("/a", "/b"), wt("/b", "/a"), wt("/c", "/a"), wt("/d", "/d")))
    assertEquals(listOf("/a", "/b", "/d"), nested.map { it.node.path })
    assertEquals(listOf("/c"), nested[0].children.map { it.node.path })
  }

  fun testAPromptGoesDownTheLineageToOpenTabsOrANewClaude() {
    fun wt(p: String) = WorktreeNode(p, p, null, false, emptyList())
    val tree = NestedWorktree(wt("/a"), listOf(NestedWorktree(wt("/b"), listOf(NestedWorktree(wt("/c"), emptyList()))), NestedWorktree(wt("/d"), emptyList())))
    assertEquals(listOf("/a", "/b", "/c", "/d"), tree.lineage())
    val tabs = mapOf("/a" to listOf("t1"), "/c" to listOf("t2", "t3"))
    assertEquals(
      listOf(PromptTarget.Type("t1"), PromptTarget.Start("/b"), PromptTarget.Type("t2"), PromptTarget.Type("t3"), PromptTarget.Start("/d")),
      PromptPropagation.targets(tree) { tabs[it].orEmpty() },
    )
  }

  fun testSubmitPastesThePromptThenSendsEnterApart() {
    val sent = ArrayList<String>()
    val later = ArrayList<Runnable>()
    val io = object : dev.phantom.ai.terminal.TerminalIo {
      override fun snapshot() = throw UnsupportedOperationException()
      override fun send(text: String) { sent += text }
      override fun onChange(parent: com.intellij.openapi.Disposable, listener: () -> Unit) = Unit
    }
    io.submit("a\nb") { later += it }
    assertEquals(listOf("\u001b[200~a\nb\u001b[201~"), sent)
    later.single().run()
    assertEquals("\r", sent.last())
  }

  fun testPorcelainParserListsMainLinkedAndDetached() {
    val out = WorktreePorcelain.parse(
      listOf("worktree /r/app", "HEAD abc", "branch refs/heads/main", "", "worktree /w/x", "HEAD def", "detached", "", "worktree /w/y", "branch refs/heads/a/b", ""),
    )
    assertEquals(listOf("/r/app" to "main", "/w/x" to null, "/w/y" to "a/b"), out.map { it.path to it.branch })
    assertEquals(listOf(true, false, false), out.map { it.isMain })
  }
}

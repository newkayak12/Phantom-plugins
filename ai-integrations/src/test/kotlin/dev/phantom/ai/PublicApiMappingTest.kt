// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.api.AiAgent
import dev.phantom.ai.api.AiSessionStatus
import dev.phantom.ai.api.ApiMapping
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.sidebar.PrInfo
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.SidebarModel
import dev.phantom.ai.sidebar.Worktree
import dev.phantom.ai.sidebar.WorktreeSource
import junit.framework.TestCase
import java.nio.file.Files

class PublicApiMappingTest : TestCase() {
  fun testEveryStatusMapsAndNullIsNone() {
    assertEquals(AiSessionStatus.WORKING, ApiMapping.status(AgentStatus.WORKING))
    assertEquals(AiSessionStatus.WAITING, ApiMapping.status(AgentStatus.WAITING))
    assertEquals(AiSessionStatus.DONE, ApiMapping.status(AgentStatus.DONE))
    assertEquals(AiSessionStatus.NONE, ApiMapping.status(null))
  }

  fun testEveryKindMaps() {
    assertEquals(AiAgent.CLAUDE, ApiMapping.agent(AgentKind.CLAUDE))
    assertEquals(AiAgent.CODEX, ApiMapping.agent(AgentKind.CODEX))
  }

  fun testSessionFieldsAndTitlePrecedence() {
    val s = AgentSession("t1", "/w/a", AgentKind.CODEX, AgentStatus.DONE, startedAt = 42, userTitle = " Mine ", firstPrompt = "first\nsecond")
    val api = ApiMapping.session(s)
    assertEquals("t1", api.tabId)
    assertEquals("/w/a", api.worktreePath)
    assertEquals(AiAgent.CODEX, api.agent)
    assertEquals(AiSessionStatus.DONE, api.status)
    assertEquals(42L, api.startedAt)
    assertEquals("Mine", api.title)
    assertEquals("first", ApiMapping.session(s.copy(userTitle = " ")).title)
    assertEquals("Codex", ApiMapping.session(s.copy(userTitle = null, firstPrompt = null)).title)
    assertEquals("Claude", ApiMapping.session(s.copy(kind = AgentKind.CLAUDE, userTitle = null, firstPrompt = "")).title)
  }

  fun testSubagentsMapInOrderWithTheirRunningState() {
    val s = dev.phantom.ai.core.AgentSession(
      "t", "/r", AgentKind.CLAUDE,
      subagents = listOf(dev.phantom.ai.core.Subagent("a1", "Explore"), dev.phantom.ai.core.Subagent("a2", "Plan", running = false)),
    )
    assertEquals(
      listOf(dev.phantom.ai.api.AiSubagent("a1", "Explore", true), dev.phantom.ai.api.AiSubagent("a2", "Plan", false)),
      ApiMapping.session(s).subagents,
    )
    assertEquals(emptyList<dev.phantom.ai.api.AiSubagent>(), ApiMapping.session(s.copy(subagents = emptyList())).subagents)
  }

  fun testWorktreeTitleBranchPrAndMain() {
    val source = WorktreeSource { listOf(Worktree("/r/app", "main", true), Worktree("/w/feat", "feat/x", false), Worktree("/w/det", null, false)) }
    val withPr = object : PrTitles {
      override fun titleFor(repoPath: String, branch: String) = infoFor(repoPath, branch)?.title
      override fun infoFor(repoPath: String, branch: String) = if (branch == "feat/x") PrInfo(7, "Add x") else null
    }
    val m = SidebarModel.build(listOf("/r/app"), source, emptyList(), emptyList(), withPr, { p -> if (p == "/r/app") "Home" else null }, 0)
    val wts = ApiMapping.worktrees(m).associateBy { it.path }
    assertEquals("Home", wts.getValue("/r/app").title)
    assertTrue(wts.getValue("/r/app").isMain)
    assertEquals("feat/x", wts.getValue("/w/feat").branch)
    assertEquals(7, wts.getValue("/w/feat").prNumber)
    assertEquals("Add x", wts.getValue("/w/feat").title)
    assertFalse(wts.getValue("/w/feat").isMain)
    assertNull(wts.getValue("/w/det").branch)
    // the no-PR model the API builds gives null
    val none = SidebarModel.build(listOf("/r/app"), source, emptyList(), emptyList(), { _, _ -> null }, { null }, 0)
    assertTrue(ApiMapping.worktrees(none).all { it.prNumber == null })
    assertEquals("feat/x", ApiMapping.worktrees(none).single { it.path == "/w/feat" }.title)
  }

  fun testWorktreesAreDistinctAcrossRepos() {
    val source = WorktreeSource { listOf(Worktree("/shared", "a", true)) }
    val m = SidebarModel.build(listOf("/r/one", "/r/two"), source, emptyList(), emptyList(), { _, _ -> null }, { null }, 0)
    assertEquals(1, ApiMapping.worktrees(m).size)
  }

  fun testSessionsAreFilteredToTheProjectWorktreesWithNormalisedPaths() {
    val real = Files.createTempDirectory("aiapi").toRealPath()
    try {
      val link = real.parent.resolve("aiapi-link-" + real.fileName)
      Files.createSymbolicLink(link, real)
      try {
        val source = WorktreeSource { listOf(Worktree("/w/a/", "a", true), Worktree(real.toString(), "b", false)) }
        val m = SidebarModel.build(listOf("/r"), source, emptyList(), emptyList(), { _, _ -> null }, { null }, 0)
        val wts = ApiMapping.worktrees(m)
        val all = listOf(
          AgentSession("slash", "/w/a", AgentKind.CLAUDE),
          AgentSession("link", link.toString() + "/", AgentKind.CODEX),
          AgentSession("other", "/w/other", AgentKind.CLAUDE),
        )
        assertEquals(setOf("slash", "link"), ApiMapping.sessionsIn(all, wts).map { it.tabId }.toSet())
      } finally {
        Files.deleteIfExists(link)
      }
    } finally {
      Files.deleteIfExists(real)
    }
  }

  fun testNormalize() {
    assertEquals("/w/a", ApiMapping.normalize("/w/a///"))
    assertEquals("/w/a", ApiMapping.normalize("\\w\\a"))
    assertEquals("/", ApiMapping.normalize("/"))
  }
}

// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.AiStateData
import dev.phantom.ai.core.Group
import dev.phantom.ai.core.GroupTree
import dev.phantom.ai.core.Repo
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.dashboard.AgentDashboardFrame
import dev.phantom.ai.dashboard.AgentDashboardPanel
import dev.phantom.ai.hooks.HookEvent
import dev.phantom.ai.hooks.HookIngest
import dev.phantom.ai.hooks.SubagentEvent
import dev.phantom.ai.sidebar.PrInfo
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.Worktree
import dev.phantom.ai.terminal.AgentTerminalService
import dev.phantom.ai.terminal.StartedTerminal
import dev.phantom.ai.terminal.TerminalStarter
import javax.swing.JLabel
import javax.swing.JPanel

class DashboardInIdeTest : BasePlatformTestCase() {
  private val repo = "/r/repo"
  private lateinit var service: AgentTerminalService

  override fun setUp() {
    super.setUp()
    AiState.getInstance().loadState(AiStateData())
    AiState.getInstance().setTree(GroupTree(emptyList<Group>(), listOf(Repo(repo, null, 0))))
    service = AgentTerminalService.getInstance(project)
    service.replaceStarter(TerminalStarter { _: Disposable, _, _, _ -> StartedTerminal(JPanel()) })
  }

  override fun tearDown() {
    try {
      ToolWindowManager.getInstance(project).getToolWindow("AI Integrations")?.contentManager?.removeAllContents(true)
      SessionStore.getInstance().all().forEach { SessionStore.getInstance().remove(it.tabId) }
      AiState.getInstance().loadState(AiStateData())
    } finally {
      super.tearDown()
    }
  }

  private fun panel(): AgentDashboardPanel {
    val prs = object : PrTitles {
      override fun titleFor(repoPath: String, branch: String): String? = "Add login"
      override fun infoFor(repoPath: String, branch: String) = PrInfo(7, "Add login")
    }
    val p = AgentDashboardPanel(project, { listOf(Worktree(repo, "main", true)) }, prs)
    Disposer.register(testRootDisposable, p)
    p.show(p.buildSidebar())
    return p
  }

  private fun labels(p: JPanel) = all(p, JLabel::class.java).mapNotNull { it.text }

  private fun <T : java.awt.Component> all(c: java.awt.Component, type: Class<T>): List<T> {
    val out = ArrayList<T>()
    if (type.isInstance(c)) out.add(type.cast(c))
    if (c is java.awt.Container) c.components.forEach { out.addAll(all(it, type)) }
    return out
  }

  fun testClickFocusesTheRealTerminalTabAndClearsUnread() {
    val a = service.launch(repo, AgentKind.CLAUDE)!!
    val b = service.launch(repo, AgentKind.CODEX)!!   // b is the selected tab now
    SessionStore.getInstance().update(a) { it.copy(status = AgentStatus.DONE, unread = true, lastPrompt = "hello", lastAnswer = "world") }
    val p = panel()
    val tw = ToolWindowManager.getInstance(project).getToolWindow("AI Integrations")!!
    val selectedBefore = tw.contentManager.selectedContent
    assertEquals(tw.contentManager.contents.size, 2)
    val card = all(p, JPanel::class.java).first { it.name == "card:$a" }
    card.mouseListeners.forEach { l ->
      l.mouseClicked(java.awt.event.MouseEvent(card, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false))
    }
    val selected = tw.contentManager.selectedContent
    assertNotSame(selectedBefore, selected)
    assertEquals(tw.contentManager.contents[0], selected)
    assertFalse(SessionStore.getInstance().get(a)!!.unread)
    assertNotNull(SessionStore.getInstance().get(b))
  }

  fun testColumnsCountsEmptyStateCardContentAndSearch() {
    val a = service.launch(repo, AgentKind.CLAUDE)!!
    SessionStore.getInstance().update(a) {
      it.copy(status = AgentStatus.WORKING, lastPrompt = "do the thing", lastAnswer = "did it", subagents = listOf(dev.phantom.ai.core.Subagent("s", "Explore")))
    }
    val p = panel()
    val l = labels(p)
    assertTrue(l.toString(), "Needs attention (0)" in l && "Working (1)" in l && "Done (0)" in l)
    assertEquals(2, l.count { it == "None" })
    assertTrue(l.toString(), "#7" in l && "main" in l && "repo" in l && "> do the thing" in l && "did it" in l)
    assertTrue(l.any { it.contains("Subagents (1)") })
    assertFalse(l.any { it.contains("Explore") })
    p.toggle(a)
    assertTrue(labels(p).any { it.contains("Explore") })
    assertEquals("Total: 1", all(p, JLabel::class.java).first { it.name == "dashboard.total" }.text)
    p.setSearch("zzz")
    assertTrue(labels(p).contains("Total: 0"))
    p.setSearch("REPO")
    assertTrue(labels(p).contains("Total: 1"))
  }

  fun testSubagentHookEventsReachTheCard() {
    val a = service.launch(repo, AgentKind.CLAUDE)!!
    HookIngest.apply(a, AgentKind.CLAUDE, HookEvent("SubagentStart", null, null, null, null, SubagentEvent.START, "i1", "Plan"))
    assertEquals(listOf("Plan" to true), SessionStore.getInstance().get(a)!!.subagents.map { it.name to it.running })
    HookIngest.apply(a, AgentKind.CLAUDE, HookEvent("SubagentStop", null, null, null, null, SubagentEvent.STOP, "i1", null))
    assertEquals(false, SessionStore.getInstance().get(a)!!.subagents.single().running)
  }

  fun testDashboardIsNotAToolWindow() {
    val ids = ToolWindowManager.getInstance(project).toolWindowIds
    assertFalse(ids.toString(), ids.any { it.contains("Dashboard", ignoreCase = true) })
    assertNull(ToolWindowManager.getInstance(project).getToolWindow("Agent Dashboard"))
    assertTrue(AgentDashboardFrame::class.java.name.endsWith("AgentDashboardFrame"))
    assertNotNull(com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("dev.phantom.ai.OpenAgentDashboard"))
  }
}

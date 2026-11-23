// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.board.WorkspaceBoardPanel
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.AiStateData
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.dashboard.AgentDashboardFrame
import dev.phantom.ai.hooks.HookEvent
import dev.phantom.ai.hooks.HookIngest
import dev.phantom.ai.hooks.SubagentEvent
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.ProjectRepos
import dev.phantom.ai.sidebar.Worktree
import dev.phantom.ai.terminal.AgentTerminalService
import dev.phantom.ai.terminal.StartedTerminal
import dev.phantom.ai.terminal.TerminalStarter
import javax.swing.JComboBox
import javax.swing.JPanel

class DashboardInIdeTest : BasePlatformTestCase() {
  private lateinit var service: AgentTerminalService

  override fun setUp() {
    super.setUp()
    AiState.getInstance().loadState(AiStateData())
    service = AgentTerminalService.getInstance(project)
    service.replaceStarter(TerminalStarter { _: Disposable, _, _, _ -> StartedTerminal(JPanel()) })
  }

  override fun tearDown() {
    try {
      dev.phantom.ai.terminal.AgentTabsHost.getInstance(project).let { h -> h.tabs().forEach { h.close(it.id) } }
      SessionStore.getInstance().all().forEach { SessionStore.getInstance().remove(it.tabId) }
      AiState.getInstance().loadState(AiStateData())
    } finally {
      super.tearDown()
    }
  }

  /** The dashboard's board over this project, whose repository is [repo], and a second open project with [other]. */
  private fun dashboard(repo: String, other: String, second: com.intellij.openapi.project.Project): WorkspaceBoardPanel {
    val p = WorkspaceBoardPanel(
      project, { r -> listOf(Worktree(r, "main", true)) }, PrTitles { _, _ -> null },
      allProjects = { listOf(project, second) },
      reposOf = { if (it === project) ProjectRepos { listOf(repo) } else ProjectRepos { listOf(other) } },
    )
    Disposer.register(testRootDisposable, p)
    p.showCards(p.buildCards())
    return p
  }

  fun testTheDashboardIsTheBoardOfEveryOpenProjectWithAProjectFilter() {
    // the light fixture has one project; the default project stands in for a second open one
    val second = com.intellij.openapi.project.ProjectManager.getInstance().defaultProject
    val p = dashboard("/r/one", "/r/two", second)
    assertEquals(setOf("/r/one", "/r/two"), p.currentLayout().cardsOf("Todo").toSet())
    val filter = all(p).filterIsInstance<JComboBox<*>>().single { it.name == "board.project" }
    assertEquals(listOf("All projects", project.name), (0 until filter.itemCount).map { filter.getItemAt(it).toString() })
    p.filterProject(project.basePath)
    assertEquals(listOf("/r/one"), p.currentLayout().cardsOf("Todo"))
    p.filterProject(null)
    assertEquals(2, p.currentLayout().cardsOf("Todo").size)
  }

  fun testSessionsOfAnotherProjectShowAsBadgesOnItsCard() {
    val a = service.launch("/r/two", AgentKind.CLAUDE)!!
    val p = dashboard("/r/one", "/r/two", com.intellij.openapi.project.ProjectManager.getInstance().defaultProject)
    assertTrue(all(p).any { it.name == "badge:launched:$a" })
  }

  fun testSubagentHookEventsReachTheSession() {
    val a = service.launch("/r/one", AgentKind.CLAUDE)!!
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

  private fun all(c: java.awt.Component): List<java.awt.Component> =
    listOf(c) + ((c as? java.awt.Container)?.components?.flatMap { all(it) } ?: emptyList())
}

// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.Disposable
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.accounts.AccountsService
import dev.phantom.ai.core.AccountEntry
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.launch.LaunchCommand
import dev.phantom.ai.sidebar.SidebarActions
import dev.phantom.ai.terminal.AgentTerminalService
import dev.phantom.ai.terminal.StartedTerminal
import dev.phantom.ai.terminal.TerminalStarter
import java.nio.file.Files
import javax.swing.JPanel

class SidebarActionsInIdeTest : BasePlatformTestCase() {
  private class Started(val cwd: String, val env: Map<String, String>, val command: String)

  private val started = mutableListOf<Started>()
  private lateinit var actions: SidebarActions

  override fun setUp() {
    super.setUp()
    AgentTerminalService.getInstance(project).replaceStarter(TerminalStarter { _: Disposable, cwd, env, command ->
      started.add(Started(cwd, env, withoutMcp(command)))
      StartedTerminal(JPanel())
    })
    AiState.getInstance().setActiveAccount(AgentKind.CLAUDE, null)
    AiState.getInstance().setActiveAccount(AgentKind.CODEX, null)
    actions = SidebarActions(project)
  }

  override fun tearDown() {
    try {
      AiState.getInstance().setActiveAccount(AgentKind.CLAUDE, null)
      AiState.getInstance().setActiveAccount(AgentKind.CODEX, null)
      dev.phantom.ai.terminal.AgentTabsHost.getInstance(project).let { h -> h.tabs().forEach { h.close(it.id) } }
      SessionStore.getInstance().all().forEach { SessionStore.getInstance().remove(it.tabId) }
    } finally {
      super.tearDown()
    }
  }

  fun testNewSessionsRunExactCommandsInTheWorktreeWithoutOpeningProjects() {
    val before = SidebarActions.openProjectCount()
    actions.newSession("/w/app-feat", AgentKind.CLAUDE)!!
    actions.newSession("/w/app-feat", AgentKind.CODEX)!!
    assertEquals(listOf(LaunchCommand.launch(AgentKind.CLAUDE), LaunchCommand.launch(AgentKind.CODEX)), started.map { it.command })
    assertEquals(listOf("/w/app-feat", "/w/app-feat"), started.map { it.cwd })
    assertEquals(before, SidebarActions.openProjectCount())
  }

  fun testSessionWhoseTerminalIsGoneResumesWithItsId() {
    val gone = AgentSession("dead", "/w/app-feat", AgentKind.CLAUDE, sessionId = "sess-1")
    assertTrue(actions.canActivate(gone))
    assertNotNull(actions.activate(gone))
    assertEquals(LaunchCommand.resume(AgentKind.CLAUDE, "sess-1"), started.single().command)
    assertEquals("/w/app-feat", started.single().cwd)
  }

  fun testSessionWithoutResumeIdRunsNothing() {
    val none = AgentSession("dead2", "/w/app-feat", AgentKind.CODEX)
    assertFalse(actions.canActivate(none))
    assertNull(actions.activate(none))
    assertTrue(started.isEmpty())
  }

  fun testLiveSessionIsFocusedAndUnreadCleared() {
    val tab = actions.newSession("/w/app-feat", AgentKind.CLAUDE)!!
    SessionStore.getInstance().update(tab) { it.copy(unread = true) }
    assertEquals(tab, actions.activate(SessionStore.getInstance().get(tab)!!))
    assertEquals(1, started.size)
    assertFalse(SessionStore.getInstance().get(tab)!!.unread)
  }

  fun testResumeUsesTheAccountTheSessionRanUnderNotTheActiveOne() {
    val dirA = Files.createTempDirectory("accA").toString()
    val dirB = Files.createTempDirectory("accB").toString()
    val state = AiState.getInstance()
    state.state.accounts.add(AccountEntry().also { it.provider = "CODEX"; it.id = "ta"; it.label = "A"; it.dir = dirA })
    state.state.accounts.add(AccountEntry().also { it.provider = "CODEX"; it.id = "tb"; it.label = "B"; it.dir = dirB })
    try {
      val svc = AccountsService.getInstance()
      svc.setActive(AgentKind.CODEX, "ta")
      val tab = actions.newSession("/w/r/acc", AgentKind.CODEX)!!
      assertEquals(dirA, started.last().env["CODEX_HOME"])
      assertEquals("ta", SessionStore.getInstance().get(tab)!!.accountId)
      val ran = SessionStore.getInstance().get(tab)!!.copy(tabId = "gone", sessionId = "cx-1")
      svc.setActive(AgentKind.CODEX, "tb")
      actions.activate(ran)
      assertEquals(dirA, started.last().env["CODEX_HOME"])
      assertEquals("ta", state.sessionsOf("/w/r/acc").single { it.sessionId == "cx-1" }.accountId)
      // a session that ran under System default resumes under it while an account is active
      actions.activate(AgentSession("gone2", "/w/r/acc", AgentKind.CODEX, sessionId = "cx-2"))
      assertFalse(started.last().env.containsKey("CODEX_HOME"))
    } finally {
      AccountsService.getInstance().remove(AgentKind.CODEX, "ta")
      AccountsService.getInstance().remove(AgentKind.CODEX, "tb")
      state.state.sessions.removeAll { it.worktree == "/w/r/acc" }
      listOf(dirA, dirB).forEach { java.io.File(it).deleteRecursively() }
    }
  }
}

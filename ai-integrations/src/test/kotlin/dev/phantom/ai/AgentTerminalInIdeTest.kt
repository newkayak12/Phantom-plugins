// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.accounts.AccountsService
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.hooks.HookEnv
import dev.phantom.ai.launch.LaunchCommand
import dev.phantom.ai.terminal.AgentTerminalService
import dev.phantom.ai.terminal.StartedTerminal
import dev.phantom.ai.terminal.TerminalStarter
import java.nio.file.Files
import javax.swing.JPanel

class AgentTerminalInIdeTest : BasePlatformTestCase() {
  private class Started(val cwd: String, val env: Map<String, String>, val command: String)

  private val started = mutableListOf<Started>()
  private lateinit var service: AgentTerminalService

  override fun setUp() {
    super.setUp()
    service = AgentTerminalService.getInstance(project)
    service.replaceStarter(TerminalStarter { _: Disposable, cwd, env, command ->
      started.add(Started(cwd, env, command))
      StartedTerminal(JPanel())
    })
    AiState.getInstance().setActiveAccount(AgentKind.CLAUDE, null)
    AiState.getInstance().setActiveAccount(AgentKind.CODEX, null)
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

  fun testAgentsGetPhantomMcpAndTheConfigGoesWithTheTab() {
    val tab = service.launch("/w/repo/mcp", AgentKind.CLAUDE)!!
    val config = java.nio.file.Path.of(com.intellij.openapi.application.PathManager.getTempPath(), "phantom-ai-mcp", "$tab.json")
    assertTrue(started.last().command.contains("'--mcp-config' '$config'"))
    val json = Files.readString(config)
    assertTrue(json, json.contains("/mcp?tabId=$tab") && json.contains("Bearer ${dev.phantom.ai.hooks.HookServer.getInstance().token}"))

    service.launch("/w/repo/mcp", AgentKind.CODEX)
    val codex = started.last().command
    assertTrue(codex, codex.contains("'-c' 'mcp_servers.phantom.url=\"http://127.0.0.1:"))
    assertTrue(codex, codex.contains("'-c' 'mcp_servers.phantom.bearer_token_env_var=\"PHANTOM_AGENT_HOOK_TOKEN\"'"))

    dev.phantom.ai.terminal.AgentTabsHost.getInstance(project).close(tab)
    assertFalse("deleted with its tab", Files.exists(config))
  }

  private fun toolWindow() = ToolWindowManager.getInstance(project).getToolWindow("Phantom AI Integration")

  fun testLaunchLandsInAiToolWindowWithEnvAndCommand() {
    val projectsBefore = ProjectManager.getInstance().openProjects.size
    val tab = service.launch("/w/repo/feat", AgentKind.CLAUDE)!!
    val s = started.single()
    assertEquals("/w/repo/feat", s.cwd)
    assertEquals(LaunchCommand.launch(AgentKind.CLAUDE), withoutMcp(s.command))
    assertEquals(tab, s.env[HookEnv.TAB_ID])
    assertNotNull(s.env[HookEnv.PORT])
    assertNotNull(s.env[HookEnv.TOKEN])
    assertFalse("System default sets no account var", s.env.containsKey("CLAUDE_CONFIG_DIR"))
    assertNotNull(toolWindow())
    assertEquals(listOf("claude · feat"), dev.phantom.ai.terminal.AgentTabsHost.getInstance(project).tabs().map { it.title })
    assertNull("never the Terminal tool window", ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.contentManager?.contents?.firstOrNull())
    assertEquals(tab, SessionStore.getInstance().forWorktree("/w/repo/feat").single().tabId)
    assertEquals(projectsBefore, ProjectManager.getInstance().openProjects.size)
  }

  fun testCancellationFromStarterPropagatesAndLeavesNoSession() {
    for (cancel in listOf<RuntimeException>(java.util.concurrent.CancellationException("x"), com.intellij.openapi.progress.ProcessCanceledException())) {
      service.replaceStarter(TerminalStarter { _: Disposable, _, _, _ -> throw cancel })
      try {
        service.launch("/w/repo/cancel", AgentKind.CLAUDE)
        fail("cancellation must propagate: $cancel")
      } catch (e: RuntimeException) {
        assertSame(cancel, e)
      }
      assertTrue(SessionStore.getInstance().forWorktree("/w/repo/cancel").isEmpty())
    }
  }

  fun testAddedAccountSetsAccountVariableOnlyForIt() {
    val acc = AccountsService.getInstance().let { svc ->
      val dir = Files.createTempDirectory("acc")
      Files.writeString(dir.resolve("auth.json"), """{"OPENAI_API_KEY":"k"}""")
      val state = AiState.getInstance()
      state.state.accounts.add(
        dev.phantom.ai.core.AccountEntry().also { it.provider = "CODEX"; it.id = "t1"; it.label = "T"; it.dir = dir.toString() },
      )
      svc.setActive(AgentKind.CODEX, "t1")
      dir.toString()
    }
    try {
      service.launch("/w/r/x", AgentKind.CODEX)
      assertEquals(acc, started.single().env["CODEX_HOME"])
      assertEquals(LaunchCommand.launch(AgentKind.CODEX), withoutMcp(started.single().command))
      service.launch("/w/r/x", AgentKind.CLAUDE)
      assertFalse(started.last().env.containsKey("CLAUDE_CONFIG_DIR"))
    } finally {
      AccountsService.getInstance().remove(AgentKind.CODEX, "t1")
    }
  }

  fun testResumeUsesHookSessionIdAndNoIdRunsNothing() {
    val none = AgentSession("old-0", "/w/r/none", AgentKind.CODEX)
    assertNull(service.resume(none))
    assertTrue(started.isEmpty())
    assertFalse(service.canResume(none))

    val s = AgentSession("old-1", "/w/r/y", AgentKind.CODEX, sessionId = "abc'd")
    assertTrue(service.canResume(s))
    val tab = service.resume(s)!!
    assertEquals(LaunchCommand.resume(AgentKind.CODEX, "abc'd"), withoutMcp(started.single().command))
    assertEquals("abc'd", SessionStore.getInstance().get(tab)!!.sessionId)
  }

  fun testFocusSelectsTabAndClearsUnread() {
    val a = service.launch("/w/r/a", AgentKind.CLAUDE)!!
    service.launch("/w/r/b", AgentKind.CLAUDE)!!
    SessionStore.getInstance().update(a) { it.copy(unread = true) }
    assertTrue(service.focus(a))
    assertEquals("claude · a", dev.phantom.ai.terminal.AgentTabsHost.getInstance(project).selected()!!.title)
    assertFalse(SessionStore.getInstance().get(a)!!.unread)
    assertFalse(service.focus("nope"))
  }

  fun testClosingTabRemovesSession() {
    val a = service.launch("/w/r/c", AgentKind.CLAUDE)!!
    dev.phantom.ai.terminal.AgentTabsHost.getInstance(project).close(dev.phantom.ai.terminal.AgentTabsHost.getInstance(project).tabs().single().id)
    assertNull(SessionStore.getInstance().get(a))
  }
}

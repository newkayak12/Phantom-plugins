// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.launch.LaunchCommand
import dev.phantom.ai.terminal.AgentTabsHost
import dev.phantom.ai.terminal.AgentTerminalService
import dev.phantom.ai.terminal.DetachedSessions
import dev.phantom.ai.terminal.StartedTerminal
import dev.phantom.ai.terminal.TerminalStarter
import dev.phantom.ai.terminal.Tmux
import dev.phantom.ai.terminal.TmuxSessions
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import javax.swing.JPanel

/** Agents in tmux outlive their project window (detached) and come back as tabs; the tab's own close and IDE exit end them. */
class DetachedSessionsInIdeTest : BasePlatformTestCase() {
  private val commands = Collections.synchronizedList(mutableListOf<String>())
  private val killed = Collections.synchronizedList(mutableListOf<String>())
  private val running = Collections.synchronizedSet(mutableSetOf<String>())
  private var tmuxCount = 0
  private var inTmux = true
  private lateinit var service: AgentTerminalService
  private lateinit var realTmux: TmuxSessions
  private val host get() = AgentTabsHost.getInstance(project)
  private val store get() = SessionStore.getInstance()

  override fun setUp() {
    super.setUp()
    service = AgentTerminalService.getInstance(project)
    // each terminal its own tmux session, as the real starter does when tmux is installed
    service.replaceStarter(TerminalStarter { _: Disposable, _, _, command ->
      commands.add(command)
      val name = if (inTmux) "phantom-t${++tmuxCount}".also { running.add(it) } else null
      StartedTerminal(JPanel(), tmuxSession = name)
    })
    realTmux = Tmux.replaceSessions(object : TmuxSessions {
      override fun exists(name: String) = name in running
      override fun kill(name: String) {
        running.remove(name)
        killed.add(name)
      }
    })
  }

  override fun tearDown() {
    try {
      Tmux.replaceSessions(realTmux)
      DetachedSessions.resetExiting()
      host.let { h -> h.tabs().forEach { h.close(it.id) } }
      store.all().forEach { store.remove(it.tabId) }
    } finally {
      super.tearDown()
    }
  }

  /** What closing the project window does to its tabs (AgentTabsHost is a project service: the project disposes it). */
  private fun closeProjectWindow() = host.dispose()

  private fun mcpConfig(tab: String) = Path.of(PathManager.getTempPath(), "phantom-ai-mcp", "$tab.json")

  /** Kills run off the EDT; waits for [name]'s. */
  private fun awaitKilled(name: String) {
    val end = System.currentTimeMillis() + 5_000
    while (name !in killed && System.currentTimeMillis() < end) Thread.sleep(20)
    assertTrue("$name killed: $killed", name in killed)
  }

  fun testProjectCloseDetachesTheTmuxAgentAndKeepsItsSession() {
    val tab = service.launch("/w/keep/a", AgentKind.CLAUDE)!!
    assertEquals("phantom-t1", store.get(tab)!!.tmuxSession)
    assertFalse(store.get(tab)!!.detached)

    closeProjectWindow()

    assertTrue(host.tabs().isEmpty())
    val s = store.get(tab)!!
    assertTrue("kept, marked detached", s.detached)
    assertEquals("phantom-t1", s.tmuxSession)
    assertTrue("MCP config kept for the running agent", Files.exists(mcpConfig(tab)))
    Thread.sleep(300)
    assertTrue("never killed: $killed", killed.isEmpty())
    // hooks still reach it
    assertNotNull(store.update(tab) { it.copy(activity = "Edit A.kt") })
  }

  fun testClosingTheTabKillsAndRemovesAsBefore() {
    val tab = service.launch("/w/keep/a", AgentKind.CLAUDE)!!
    host.close(tab)
    assertNull(store.get(tab))
    assertFalse(Files.exists(mcpConfig(tab)))
    awaitKilled("phantom-t1")
  }

  fun testWithoutTmuxProjectCloseEndsTheSessionAsBefore() {
    inTmux = false
    val tab = service.launch("/w/keep/a", AgentKind.CLAUDE)!!
    assertNull(store.get(tab)!!.tmuxSession)
    closeProjectWindow()
    assertNull(store.get(tab))
    assertFalse(Files.exists(mcpConfig(tab)))
  }

  fun testIdeExitKillsDetachedAndOpenAgents() {
    val detached = service.launch("/w/keep/a", AgentKind.CLAUDE)!!
    closeProjectWindow()
    val open = service.launch("/w/keep/b", AgentKind.CLAUDE)!!

    DetachedSessions.exit()
    assertEquals("detached ones end at once", listOf("phantom-t1"), killed.toList())
    assertNull(store.get(detached))
    assertFalse(Files.exists(mcpConfig(detached)))

    // the projects close after: their tabs kill instead of detaching
    closeProjectWindow()
    assertNull(store.get(open))
    awaitKilled("phantom-t2")
  }

  fun testReattachKeepsTheTabIdAndClearsDetached() {
    val tab = service.launch("/w/keep/a", AgentKind.CLAUDE)!!
    closeProjectWindow()
    val before = store.get(tab)!!

    assertTrue(service.canResume(before))
    assertEquals(tab, service.resume(before))
    assertEquals(Tmux.attach("tmux", "phantom-t1"), commands.last())
    assertEquals(listOf(tab), host.tabs().map { it.id })
    val s = store.get(tab)!!
    assertFalse(s.detached)
    assertEquals("phantom-t1", s.tmuxSession)
    assertNull("attached once only", service.reattach(tab))
    assertEquals(1, host.tabs().size)

    // a tab like any: the project closing detaches it again, closing the tab kills it
    closeProjectWindow()
    assertTrue(store.get(tab)!!.detached)
    assertEquals(tab, service.reattach(tab))
    host.close(tab)
    assertNull(store.get(tab))
    awaitKilled("phantom-t1")
  }

  fun testReattachFallsBackToResumeWhenTheTmuxSessionIsGone() {
    val tab = service.launch("/w/keep/a", AgentKind.CLAUDE)!!
    store.update(tab) { it.copy(sessionId = "sess-1") }
    closeProjectWindow()
    running.clear()

    val next = service.resume(store.get(tab)!!)!!
    assertFalse("a new tab", tab == next)
    assertNull("the dead session is dropped", store.get(tab))
    assertEquals(LaunchCommand.resume(AgentKind.CLAUDE, "sess-1"), withoutMcp(commands.last()))
    assertEquals("sess-1", store.get(next)!!.sessionId)
  }

  fun testOpeningAProjectReattachesOnlyItsWorktreesDetachedSessions() {
    running += listOf("p1", "p2", "p3")
    store.put(AgentSession("d1", "/w/keep/a", AgentKind.CLAUDE, tmuxSession = "p1", detached = true))
    store.put(AgentSession("d2", "/w/keep/b", AgentKind.CLAUDE, tmuxSession = "p2", detached = true))
    store.put(AgentSession("d3", "/w/keep/a/", AgentKind.CODEX, tmuxSession = "p3", detached = false))

    service.reattachDetached("/w/keep/a")

    assertEquals(listOf("d1"), host.tabs().map { it.id })
    assertEquals(listOf(Tmux.attach("tmux", "p1")), commands.toList())
    assertFalse(store.get("d1")!!.detached)
    assertTrue("another worktree's stays detached", store.get("d2")!!.detached)
  }
}

// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.api.PhantomAiApiImpl
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.dashboard.AgentDashboardPanel
import dev.phantom.ai.launch.LaunchCommand
import dev.phantom.ai.memo.AiMemoStore
import dev.phantom.ai.memo.MemoSender
import dev.phantom.ai.memo.MemoTarget
import dev.phantom.ai.memo.SendOutcome
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.ProjectRepos
import dev.phantom.ai.sidebar.ProjectSwitcher
import dev.phantom.ai.sidebar.SidebarActions
import dev.phantom.ai.sidebar.Worktree
import dev.phantom.ai.sidebar.WorktreeSource
import dev.phantom.ai.terminal.AgentTabsHost
import dev.phantom.ai.terminal.AgentTerminalService
import dev.phantom.ai.terminal.DetachedSessions
import dev.phantom.ai.terminal.DetachedSessionsUnload
import dev.phantom.ai.terminal.StartedTerminal
import dev.phantom.ai.terminal.ScreenSnapshot
import dev.phantom.ai.terminal.TerminalIo
import dev.phantom.ai.terminal.TerminalStarter
import dev.phantom.ai.terminal.Tmux
import dev.phantom.ai.terminal.TmuxSessions
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import javax.swing.JPanel

/** Agents in tmux outlive their project window (detached) and come back as tabs; the tab's own close and plugin unload end them. */
class DetachedSessionsInIdeTest : BasePlatformTestCase() {
  private class FakeIo : TerminalIo {
    val sent = Collections.synchronizedList(mutableListOf<String>())
    override fun snapshot() = ScreenSnapshot(emptyList(), 80, 24, 0, 0)
    override fun send(text: String) {
      sent += text
    }
    override fun onChange(parent: Disposable, listener: () -> Unit) {}
  }

  private val commands = Collections.synchronizedList(mutableListOf<String>())
  private val killed = Collections.synchronizedList(mutableListOf<String>())
  private val killedTogether = Collections.synchronizedList(mutableListOf<Set<String>>())
  private val running = Collections.synchronizedSet(mutableSetOf<String>())
  private val ios = Collections.synchronizedList(mutableListOf<FakeIo>())
  @Volatile private var tmuxUnknown = false
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
      StartedTerminal(JPanel(), io = FakeIo().also { ios.add(it) }, tmuxSession = name)
    })
    realTmux = Tmux.replaceSessions(object : TmuxSessions {
      override fun exists(name: String): Boolean? = if (tmuxUnknown) null else name in running
      override fun kill(name: String) {
        running.remove(name)
        killed.add(name)
      }
      override fun killAll(names: Collection<String>) {
        killedTogether.add(names.toSet())
        names.forEach(::kill)
      }
    })
    DetachedSessions.resetOwned()
  }

  override fun tearDown() {
    try {
      Tmux.replaceSessions(realTmux)
      DetachedSessions.resetUnloading()
      DetachedSessions.resetOwned()
      host.reopen()
      host.let { h -> h.tabs().forEach { h.close(it.id) } }
      store.all().forEach { store.remove(it.tabId) }
    } finally {
      super.tearDown()
    }
  }

  /**
   * What closing the project window does to its tabs (AgentTabsHost is a project service: the project disposes it). The
   * light test project lives on, so the host is reopened after unless [reopen] is false.
   */
  private fun closeProjectWindow(reopen: Boolean = true) {
    host.dispose()
    if (reopen) host.reopen()
  }

  private fun mcpConfig(tab: String) = Path.of(PathManager.getTempPath(), "phantom-ai-mcp", "$tab.json")

  /** Kills run off the EDT; waits for [name]'s. */
  private fun awaitKilled(name: String) {
    val end = System.currentTimeMillis() + 5_000
    while (name !in killed && System.currentTimeMillis() < end) Thread.sleep(20)
    assertTrue("$name killed: $killed", name in killed)
  }

  private fun detachedTab(worktree: String = "/w/keep/a"): String {
    val tab = service.launch(worktree, AgentKind.CLAUDE)!!
    closeProjectWindow()
    assertTrue(store.get(tab)!!.detached)
    commands.clear()
    return tab
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
    assertEquals("remembers the window it was in", project.basePath, s.detachedFrom)
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

  fun testAQuitThatDidNotHappenLeavesDetachWorking() {
    // the exit state is read live from the application (reset when a quit is cancelled); nothing sticks from a quit attempt
    assertFalse(ApplicationManager.getApplication().isExitInProgress)
    assertFalse(DetachedSessions.exiting())
    assertTrue(DetachedSessions.mayDetach())
    val tab = detachedTab()
    val again = service.launch("/w/keep/b", AgentKind.CLAUDE)!!
    closeProjectWindow()
    assertTrue(store.get(tab)!!.detached)
    assertTrue(store.get(again)!!.detached)
    assertTrue(killed.isEmpty())
  }

  fun testPluginUnloadKillsDetachedAndOpenAgents() {
    val detached = detachedTab()
    val open = service.launch("/w/keep/b", AgentKind.CLAUDE)!!

    val self = PluginManagerCore.getPlugin(PluginId.getId(DetachedSessions.PLUGIN_ID))!!
    val other = PluginManagerCore.getPlugin(PluginId.getId("com.intellij"))!!
    DetachedSessionsUnload().beforePluginUnload(other, isUpdate = true)
    assertTrue("another plugin's unload changes nothing", store.get(detached)!!.detached)

    DetachedSessionsUnload().beforePluginUnload(self, isUpdate = true)
    assertEquals("attached and detached end together, once", listOf(setOf("phantom-t1", "phantom-t2")), killedTogether.toList())
    assertNull(store.get(detached))
    assertNull(store.get(open))
    assertFalse(Files.exists(mcpConfig(detached)))
    assertFalse(Files.exists(mcpConfig(open)))
    assertTrue(DetachedSessions.owned().isEmpty())
  }

  fun testAfterUnloadTabDisposalLooksNothingUpAndKillsNothing() {
    val open = service.launch("/w/keep/b", AgentKind.CLAUDE)!!
    DetachedSessions.unload()
    val kills = killed.toList()
    // the store and the config would be what a tab's disposal touches: put back, they must stay untouched
    store.put(AgentSession(open, "/w/keep/b", AgentKind.CLAUDE))
    Files.createDirectories(mcpConfig(open).parent)
    Files.writeString(mcpConfig(open), "{}")
    try {
      closeProjectWindow()
      Thread.sleep(300)
      assertNotNull("not removed by the tab's disposal", store.get(open))
      assertFalse("not marked detached either", store.get(open)!!.detached)
      assertTrue("config not deleted", Files.exists(mcpConfig(open)))
      assertEquals("no per-tab kill", kills, killed.toList())
    } finally {
      Files.deleteIfExists(mcpConfig(open))
    }
  }

  fun testUnloadKillsOnlyTheSessionsThisIdeStarted() {
    detachedTab()
    service.launch("/w/keep/b", AgentKind.CLAUDE)!!
    store.put(AgentSession("foreign", "/w/x", AgentKind.CLAUDE, tmuxSession = "phantom-other-ide", detached = true))
    DetachedSessions.unload()
    assertEquals(listOf(setOf("phantom-t1", "phantom-t2")), killedTogether.toList())
    assertFalse("phantom-other-ide" in killed)
  }

  fun testShutdownKillsOnlyTheSessionsThisIdeStarted() {
    val closed = service.launch("/w/keep/a", AgentKind.CLAUDE)!!
    host.close(closed)
    awaitKilled("phantom-t1")
    detachedTab("/w/keep/b")
    service.launch("/w/keep/c", AgentKind.CLAUDE)!!
    store.put(AgentSession("foreign", "/w/x", AgentKind.CLAUDE, tmuxSession = "phantom-other-ide", detached = true))
    DetachedSessions.killOwned()
    assertEquals("the detached and the attached, not the closed nor a foreign one", listOf(setOf("phantom-t2", "phantom-t3")), killedTogether.toList())
    assertTrue(DetachedSessions.owned().isEmpty())
  }

  fun testNoBindingTaskSurvivesUnload() {
    service.launch("/w/keep/a", AgentKind.CLAUDE)!!
    assertTrue("a hook-less tab polls for its file", DetachedSessions.liveTasks() > 0)
    DetachedSessions.unload()
    assertEquals(0, DetachedSessions.liveTasks())
    val late = java.util.concurrent.CompletableFuture<Unit>()
    assertFalse("tracked after unload: cancelled at once", DetachedSessions.track(late))
    assertTrue(late.isCancelled)
  }

  fun testReattachKeepsTheTabIdAndClearsDetached() {
    val tab = detachedTab()
    val before = store.get(tab)!!

    assertTrue(service.canResume(before))
    assertEquals(tab, service.resume(before))
    assertEquals(Tmux.attach("tmux", "phantom-t1"), commands.last())
    assertEquals(listOf(tab), host.tabs().map { it.id })
    val s = store.get(tab)!!
    assertFalse(s.detached)
    assertNull(s.detachedFrom)
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
    assertEquals("killed anyway before dropping", listOf("phantom-t1"), killed.toList())
    assertEquals(LaunchCommand.resume(AgentKind.CLAUDE, "sess-1"), withoutMcp(commands.last()))
    assertEquals("sess-1", store.get(next)!!.sessionId)
  }

  fun testUnknownTmuxStateKeepsTheSessionDetachedAndRunsNothing() {
    val tab = service.launch("/w/keep/a", AgentKind.CLAUDE)!!
    store.update(tab) { it.copy(sessionId = "sess-1") }
    closeProjectWindow()
    commands.clear()
    tmuxUnknown = true

    assertNull(service.resume(store.get(tab)!!))
    assertTrue("no --resume beside a maybe-running agent", commands.isEmpty())
    assertTrue(store.get(tab)!!.detached)
    assertTrue(killed.isEmpty())
    assertTrue(host.tabs().isEmpty())
  }

  fun testNoReattachIntoAClosingWindow() {
    val tab = service.launch("/w/keep/a", AgentKind.CLAUDE)!!
    closeProjectWindow(reopen = false)
    commands.clear()
    assertNull(service.reattach(tab))
    assertTrue(store.get(tab)!!.detached)
    assertTrue(commands.isEmpty())
    assertTrue(host.tabs().isEmpty())
  }

  fun testOpeningAProjectReattachesWhatDetachedFromItsWindow() {
    val dir = Files.createTempDirectory("keep-win")
    try {
      running += listOf("p1", "p2", "p3")
      store.put(AgentSession("d1", "/w/any/worktree", AgentKind.CLAUDE, tmuxSession = "p1", detached = true, detachedFrom = "$dir/./"))
      store.put(AgentSession("d2", dir.toString(), AgentKind.CLAUDE, tmuxSession = "p2", detached = true, detachedFrom = "/w/other-window"))
      store.put(AgentSession("d3", "/w/x", AgentKind.CODEX, tmuxSession = "p3", detached = false, detachedFrom = dir.toString()))

      service.reattachDetached(dir.toRealPath().toString())

      assertEquals(listOf("d1"), host.tabs().map { it.id })
      assertEquals(listOf(Tmux.attach("tmux", "p1")), commands.toList())
      assertFalse(store.get("d1")!!.detached)
      assertTrue("another window's stays detached, whatever its worktree", store.get("d2")!!.detached)
    } finally {
      dir.toFile().deleteRecursively()
    }
  }

  fun testTheWindowItDetachedFromGetsItBack() {
    val tab = detachedTab()
    service.reattachDetached(project.basePath!!)
    assertEquals(listOf(tab), host.tabs().map { it.id })
  }

  fun testSessionTreeClickReattachesHere() {
    val tab = detachedTab()
    assertEquals(tab, SidebarActions(project).activate(store.get(tab)!!))
    assertEquals(listOf(tab), host.tabs().map { it.id })
    assertEquals(listOf(Tmux.attach("tmux", "phantom-t1")), commands.toList())
  }

  fun testDashboardCardClickReattachesHere() {
    val tab = detachedTab()
    val prs = PrTitles { _, _ -> null }
    val panel = AgentDashboardPanel(project, { emptyList() }, prs, { ProjectRepos { emptyList() } })
    Disposer.register(testRootDisposable, panel)
    panel.open(tab)
    assertEquals(listOf(tab), host.tabs().map { it.id })
    assertFalse(store.get(tab)!!.detached)
  }

  fun testApiFocusSessionReattachesHere() {
    val tab = detachedTab()
    val api = PhantomAiApiImpl(
      reposOf = { ProjectRepos { emptyList() } },
      sourceOf = { WorktreeSource { emptyList<Worktree>() } },
      projects = { listOf(project) },
      pollIntervalMs = 0,
    )
    Disposer.register(testRootDisposable, api)
    assertTrue(api.focusSession(project, tab))
    assertEquals(listOf(tab), host.tabs().map { it.id })
  }

  fun testMemoToADetachedSessionReattachesItThenSends() {
    val tab = detachedTab("/src")
    store.update(tab) { it.copy(status = AgentStatus.DONE) }
    val memos = AiMemoStore(project) { "/src" }
    Disposer.register(testRootDisposable, memos)
    val sender = MemoSender(project, memos) { path -> VirtualFileManager.getInstance().findFileByUrl("temp://$path") }
    Disposer.register(testRootDisposable, sender)
    myFixture.configureByText("A.java", (0..9).joinToString("\n") { "line $it" })
    val memo = memos.add(myFixture.editor.document, 1, 2, "fix")
    ios.clear()

    assertEquals(SendOutcome.SENT, sender.send(MemoTarget.Session(tab, "t", AgentStatus.DONE), listOf(memo.id)))
    assertEquals(listOf(tab), host.tabs().map { it.id })
    assertTrue(ios.single().sent.isNotEmpty())

    // gone and no tab anywhere: the pick fails, the memos stay
    host.close(tab)
    store.put(AgentSession(tab, "/src", AgentKind.CLAUDE, status = AgentStatus.DONE, tmuxSession = "phantom-gone", detached = true))
    val memo2 = memos.add(myFixture.editor.document, 3, 4, "fix")
    assertEquals(SendOutcome.FAILED, sender.send(MemoTarget.Session(tab, "t", AgentStatus.DONE), listOf(memo2.id)))
    assertEquals(1, memos.all().size)
  }

  fun testATabInAnotherWindowIsFocusedThereNotResumed() {
    val dir = Files.createTempDirectory("keep-other")
    try {
      Files.createDirectories(dir.resolve(".idea"))
      val other = PlatformTestUtil.loadAndOpenProject(dir, testRootDisposable)
      val otherService = AgentTerminalService.getInstance(other)
      otherService.replaceStarter(TerminalStarter { _: Disposable, _, _, command ->
        commands.add(command)
        StartedTerminal(JPanel())
      })
      val tab = otherService.launch("/w/keep/a", AgentKind.CLAUDE)!!
      store.update(tab) { it.copy(sessionId = "sess-1") }
      commands.clear()
      val switched = mutableListOf<String>()

      assertEquals(tab, SidebarActions(project, ProjectSwitcher { switched.add(it); true }).activate(store.get(tab)!!))
      assertTrue("no second agent", commands.isEmpty())
      assertTrue(host.tabs().isEmpty())
      assertEquals(listOf(other.basePath), switched)
      assertEquals(tab, AgentTabsHost.getInstance(other).selected()!!.id)
      AgentTabsHost.getInstance(other).close(tab)
    } finally {
      dir.toFile().deleteRecursively()
    }
  }
}

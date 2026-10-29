// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.api.AiAgent
import dev.phantom.ai.api.AiChangeListener
import dev.phantom.ai.api.AiSessionStatus
import dev.phantom.ai.api.PhantomAiApi
import dev.phantom.ai.api.PhantomAiApiImpl
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.sidebar.ProjectRepos
import dev.phantom.ai.sidebar.Worktree
import dev.phantom.ai.sidebar.WorktreeSource
import dev.phantom.ai.terminal.AgentTabsHost
import dev.phantom.ai.terminal.AgentTerminalService
import dev.phantom.ai.terminal.StartedTerminal
import dev.phantom.ai.terminal.TerminalStarter
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel

class PublicApiInIdeTest : BasePlatformTestCase() {
  @Volatile private var trees: List<Worktree> = listOf(Worktree("/r/app", "main", true))
  private val started = mutableListOf<Pair<String, String>>()
  private lateinit var impl: PhantomAiApiImpl

  private fun newImpl(intervalMs: Long) = PhantomAiApiImpl(
    reposOf = { ProjectRepos { listOf("/r/app") } },
    sourceOf = { WorktreeSource { trees } },
    projects = { listOf(project) },
    pollIntervalMs = intervalMs,
  )

  override fun setUp() {
    super.setUp()
    impl = newImpl(0)
    AgentTerminalService.getInstance(project).replaceStarter(TerminalStarter { _: Disposable, cwd, _, command ->
      started.add(cwd to command)
      StartedTerminal(JPanel())
    })
    AiState.getInstance().setActiveAccount(AgentKind.CLAUDE, null)
    AiState.getInstance().setActiveAccount(AgentKind.CODEX, null)
  }

  override fun tearDown() {
    try {
      Disposer.dispose(impl)
      AgentTabsHost.getInstance(project).let { h -> h.tabs().forEach { h.close(it.id) } }
      SessionStore.getInstance().all().forEach { SessionStore.getInstance().remove(it.tabId) }
    } finally {
      super.tearDown()
    }
  }

  private fun counter(parent: Disposable): AtomicInteger {
    val n = AtomicInteger()
    impl.addChangeListener(parent, AiChangeListener { n.incrementAndGet() })
    return n
  }

  fun testApiInstanceIsAvailable() {
    assertNotNull(PhantomAiApi.getInstance())
    assertTrue(PhantomAiApi.getInstance().sessions().isEmpty())
  }

  fun testSessionsAndWorktreesOfTheProject() {
    SessionStore.getInstance().put(AgentSession("t1", "/r/app/", AgentKind.CLAUDE, AgentStatus.WAITING, firstPrompt = "hi"))
    SessionStore.getInstance().put(AgentSession("t2", "/elsewhere", AgentKind.CODEX))
    assertEquals(listOf("t1", "t2"), impl.sessions().map { it.tabId }.sorted())
    val mine = impl.sessions(project)
    assertEquals(listOf("t1"), mine.map { it.tabId })
    assertEquals(AiSessionStatus.WAITING, mine.single().status)
    assertEquals(AiSessionStatus.NONE, impl.sessions().single { it.tabId == "t2" }.status)
    val wt = impl.worktrees(project).single()
    assertEquals("/r/app", wt.path)
    assertEquals("main", wt.branch)
    assertNull(wt.prNumber)
  }

  fun testListenerFiresOnSessionStoreAndAiStateAndStopsAfterDispose() {
    val parent = Disposer.newDisposable("api-listener")
    val n = counter(parent)
    SessionStore.getInstance().put(AgentSession("t1", "/r/app", AgentKind.CLAUDE))
    assertEquals(1, n.get())
    SessionStore.getInstance().remove("t1")
    assertEquals(2, n.get())
    AiState.getInstance().changed()
    assertEquals(3, n.get())
    Disposer.dispose(parent)
    SessionStore.getInstance().put(AgentSession("t2", "/r/app", AgentKind.CLAUDE))
    AiState.getInstance().changed()
    assertEquals(3, n.get())
  }

  fun testWorktreeWatcherFiresOncePerChange() {
    val parent = Disposer.newDisposable("api-watcher")
    val n = counter(parent)
    impl.pollWorktreesNow()
    assertEquals("the first poll is the baseline", 0, n.get())
    impl.pollWorktreesNow()
    assertEquals("unchanged", 0, n.get())
    trees = trees + Worktree("/w/feat", "feat", false)
    impl.pollWorktreesNow()
    assertEquals("added", 1, n.get())
    impl.pollWorktreesNow()
    assertEquals(1, n.get())
    trees = trees.take(1)
    impl.pollWorktreesNow()
    assertEquals("removed", 2, n.get())
    Disposer.dispose(parent)
    trees = trees + Worktree("/w/again", "again", false)
    impl.pollWorktreesNow()
    assertEquals("watcher stopped", 2, n.get())
    assertFalse(impl.isWatching())
  }

  fun testWatcherRunsOnlyWhileListenersExist() {
    val scheduled = newImpl(3_600_000)
    try {
      assertFalse(scheduled.isWatching())
      val a = Disposer.newDisposable("a")
      val b = Disposer.newDisposable("b")
      scheduled.addChangeListener(a, NOOP)
      scheduled.addChangeListener(b, NOOP)
      assertTrue(scheduled.isWatching())
      Disposer.dispose(a)
      assertTrue(scheduled.isWatching())
      Disposer.dispose(b)
      assertFalse(scheduled.isWatching())
    } finally {
      Disposer.dispose(scheduled)
    }
  }

  fun testStartAndFocusGoThroughTheTerminalService() {
    val tab = impl.startSession(project, "/r/app", AiAgent.CODEX)!!
    assertEquals("/r/app", started.single().first)
    assertTrue(started.single().second.contains("codex"))
    assertEquals(tab, SessionStore.getInstance().forWorktree("/r/app").single().tabId)
    assertEquals(AgentKind.CODEX, SessionStore.getInstance().get(tab)!!.kind)
    assertTrue(impl.focusSession(project, tab))
    assertFalse(impl.focusSession(project, "no-such-tab"))
    val claude = impl.startSession(project, "/r/app", AiAgent.CLAUDE)!!
    assertEquals(AgentKind.CLAUDE, SessionStore.getInstance().get(claude)!!.kind)
  }

  private companion object {
    val NOOP = AiChangeListener { }
  }
}

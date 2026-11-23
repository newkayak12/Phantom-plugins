// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.board.BoardCard
import dev.phantom.ai.board.BoardDialogs
import dev.phantom.ai.board.BoardWork
import dev.phantom.ai.board.CardDraft
import dev.phantom.ai.board.DoneChoice
import dev.phantom.ai.board.WorkspaceBoardPanel
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.AiStateData
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.sidebar.GitOut
import dev.phantom.ai.sidebar.GitRun
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.ProjectRepos
import dev.phantom.ai.sidebar.Worktree
import dev.phantom.ai.sidebar.WorktreeCreator
import dev.phantom.ai.terminal.AgentTab
import dev.phantom.ai.terminal.AgentTabsHost
import dev.phantom.ai.terminal.AgentTerminalService
import dev.phantom.ai.terminal.ScreenSnapshot
import dev.phantom.ai.terminal.StartedTerminal
import dev.phantom.ai.terminal.TerminalIo
import dev.phantom.ai.terminal.TerminalStarter
import java.nio.file.Files
import javax.swing.JPanel

/** The board's transitions: what moving a card does, with fake dialogs and git but the plugin's real launch and queue. */
class BoardFlowInIdeTest : BasePlatformTestCase() {
  private val repo = "/r/repo"
  private val wt = "/r/repo-feature"
  private val commands = mutableListOf<String>()
  private val git = mutableListOf<String>()
  private lateinit var root: java.io.File

  private class FakeIo : TerminalIo {
    val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
    override fun snapshot() = ScreenSnapshot(emptyList(), 80, 24, 0, 0)
    override fun send(text: String) {
      sent += text
    }
    override fun onChange(parent: Disposable, listener: () -> Unit) {}
  }

  private inner class Dialogs : BoardDialogs {
    var prompt: String? = null
    var done: DoneChoice? = null
    val asked = mutableListOf<String>()
    override fun prompt(card: BoardCard, followUp: Boolean): String? = prompt.also { asked += if (followUp) "follow-up" else "prompt" }
    override fun confirmDone(card: BoardCard): DoneChoice? = done.also { asked += "done" }
    override fun editCard(repos: List<String>, card: BoardCard?): CardDraft? = CardDraft(repo, "Login page", "add a login page", AgentKind.CLAUDE)
  }

  private val dialogs = Dialogs()

  override fun setUp() {
    super.setUp()
    AiState.getInstance().loadState(AiStateData())
    root = Files.createTempDirectory("board").toFile()
    AgentTerminalService.getInstance(project).replaceStarter(TerminalStarter { _: Disposable, _, _, command ->
      commands.add(command)
      StartedTerminal(JPanel())
    })
  }

  override fun tearDown() {
    try {
      AgentTabsHost.getInstance(project).let { h -> h.tabs().forEach { h.close(it.id) } }
      SessionStore.getInstance().all().forEach { SessionStore.getInstance().remove(it.tabId) }
      AiState.getInstance().loadState(AiStateData())
      root.deleteRecursively()
    } finally {
      super.tearDown()
    }
  }

  private val fakeGit = GitRun { _, command, args ->
    git += (listOf(command.name()) + args).joinToString(" ")
    when {
      command.name() == "rev-parse" -> GitOut(true, listOf("origin/main"))
      else -> GitOut(true, emptyList())
    }
  }

  private fun panel(): WorkspaceBoardPanel {
    val work = BoardWork(fakeGit, WorktreeCreator(fakeGit, root.path)) { it.run() }
    val p = WorkspaceBoardPanel(
      project, { r -> if (r == repo) listOf(Worktree(repo, "main", true), Worktree(wt, "feature", false)) else emptyList() },
      PrTitles { _, _ -> null }, ProjectRepos { listOf(repo) }, dialogs = dialogs, work = work,
    )
    Disposer.register(testRootDisposable, p)
    p.showCards(p.buildCards())
    return p
  }

  private fun flush() = PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

  private fun place(path: String, column: String) {
    AiState.getInstance().worktree(path).column = column
  }

  /** A live agent tab of [wt] whose terminal is [io]. */
  private fun liveSession(status: AgentStatus?, io: FakeIo): String {
    SessionStore.getInstance().put(AgentSession("t1", wt, AgentKind.CLAUDE, status = status, startedAt = 1))
    val disposable = Disposer.newDisposable("tab")
    Disposer.register(disposable) { SessionStore.getInstance().remove("t1") }
    AgentTabsHost.getInstance(project).add(AgentTab("t1", "t1", JPanel(), io = io, disposable = disposable))
    flush()
    return "t1"
  }

  fun testMoveToInProgressStartsTheCardsAgentWithItsPrompt() {
    AiState.getInstance().worktree(wt).prompt = "fix the login bug"
    val p = panel()
    p.moveCard(wt, "In progress", 0)
    assertEquals("In progress", AiState.getInstance().findWorktree(wt)?.column)
    val session = SessionStore.getInstance().forWorktree(wt).single()
    assertEquals(AgentKind.CLAUDE, session.kind)
    assertTrue(commands.toString(), commands.single().contains("fix the login bug"))
    assertTrue(dialogs.asked.isEmpty())
  }

  fun testACardWithoutPromptAsksAndCancelLeavesItInTodo() {
    val p = panel()
    dialogs.prompt = null
    p.moveCard(wt, "In progress", 0)
    assertEquals(listOf("prompt"), dialogs.asked)
    assertEquals("Todo", p.currentLayout().columnOf(wt))
    assertTrue(SessionStore.getInstance().forWorktree(wt).isEmpty())
  }

  fun testPlannedCardGetsItsWorktreeThenItsAgent() {
    val p = panel()
    p.newCard()
    val key = AiState.getInstance().state.worktrees.single { it.path!!.startsWith("card:") }.path!!
    p.showCards(p.buildCards())
    assertEquals("Todo", p.currentLayout().columnOf(key))
    p.moveCard(key, "In progress", 0)
    flush()
    assertTrue(git.toString(), git.any { it.startsWith("worktree add") && it.contains("Login-page") })
    val made = java.io.File(root, "repo/Login-page").path
    assertNull(AiState.getInstance().findWorktree(key))
    assertEquals("In progress", AiState.getInstance().findWorktree(made)?.column)
    assertEquals(made, SessionStore.getInstance().all().single().worktree)
    assertTrue(commands.single().contains("add a login page"))
  }

  fun testSessionDoneMovesTheCardToInReview() {
    place(wt, "In progress")
    SessionStore.getInstance().put(AgentSession("s1", wt, AgentKind.CLAUDE, status = AgentStatus.WORKING))
    flush()
    assertEquals("In progress", AiState.getInstance().findWorktree(wt)?.column)
    SessionStore.getInstance().update("s1") { it.copy(status = AgentStatus.DONE) }
    flush()
    assertEquals("In review", AiState.getInstance().findWorktree(wt)?.column)
    // a card the user moved back while the session still says done stays until the session finishes again
    place(wt, "In progress")
    SessionStore.getInstance().update("s1") { it.copy(unread = true) }
    flush()
    assertEquals("In progress", AiState.getInstance().findWorktree(wt)?.column)
  }

  fun testMoveBackFromReviewSendsTheFollowUpToTheSession() {
    val io = FakeIo()
    liveSession(AgentStatus.DONE, io)
    place(wt, "In review")
    val p = panel()
    dialogs.prompt = "also add a test"
    p.moveCard(wt, "In progress", 0)
    assertEquals(listOf("follow-up"), dialogs.asked)
    assertEquals("In progress", p.currentLayout().columnOf(wt))
    assertTrue(io.sent.toString(), io.sent.any { it.contains("also add a test") })
    assertTrue(commands.isEmpty())
  }

  fun testFollowUpToABusySessionWaitsUntilItIsDone() {
    val io = FakeIo()
    val tab = liveSession(AgentStatus.WORKING, io)
    place(wt, "In review")
    val p = panel()
    dialogs.prompt = "also add a test"
    p.moveCard(wt, "In progress", 0)
    assertTrue(io.sent.isEmpty())
    SessionStore.getInstance().update(tab) { it.copy(status = AgentStatus.DONE) }
    flush()
    assertTrue(io.sent.toString(), io.sent.any { it.contains("also add a test") })
  }

  fun testCancelledFollowUpLeavesTheCardInReview() {
    place(wt, "In review")
    val p = panel()
    dialogs.prompt = null
    p.moveCard(wt, "In progress", 0)
    assertEquals("In review", p.currentLayout().columnOf(wt))
  }

  fun testDoneAsksFirstAndRemovesTheWorktreeOnlyWhenChosen() {
    place(wt, "In review")
    val p = panel()
    dialogs.done = null
    p.moveCard(wt, "Done", 0)
    assertEquals("In review", p.currentLayout().columnOf(wt))
    dialogs.done = DoneChoice(removeWorktree = false)
    p.moveCard(wt, "Done", 0)
    assertEquals("Done", p.currentLayout().columnOf(wt))
    assertFalse(git.toString(), git.any { it.startsWith("worktree remove") })
    assertFalse(git.any { it.startsWith("merge") || it.startsWith("push") })
  }

  fun testDoneWithRemovalRemovesTheWorktreeButNeverMergesOrPushes() {
    place(wt, "In review")
    val p = panel()
    dialogs.done = DoneChoice(removeWorktree = true)
    p.moveCard(wt, "Done", 0)
    flush()
    assertTrue(git.toString(), git.any { it == "worktree remove $wt" })
    assertTrue(git.any { it == "branch -d feature" })
    assertFalse(git.any { it.startsWith("merge") || it.startsWith("push") })
    assertNull(AiState.getInstance().findWorktree(wt))
  }

  fun testAPlannedCardCannotGoToReview() {
    val p = panel()
    p.newCard()
    p.showCards(p.buildCards())
    val key = AiState.getInstance().state.worktrees.single { it.path!!.startsWith("card:") }.path!!
    p.moveCard(key, "In review", 0)
    assertEquals("Todo", p.currentLayout().columnOf(key))
  }

  fun testBadgeClickJumpsToTheTerminalAndClearsUnread() {
    val service = AgentTerminalService.getInstance(project)
    val a = service.launch(wt, AgentKind.CLAUDE)!!
    service.launch(repo, AgentKind.CODEX)!!
    SessionStore.getInstance().update(a) { it.copy(status = AgentStatus.WAITING, unread = true) }
    val p = panel()
    val badge = all(p).first { it.name == "badge:waiting:$a" }
    badge.mouseListeners.forEach { it.mouseClicked(java.awt.event.MouseEvent(badge, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false)) }
    assertEquals(a, AgentTabsHost.getInstance(project).selected()?.id)
    assertFalse(SessionStore.getInstance().get(a)!!.unread)
  }

  fun testChangesShowTheDiffAgainstTheBranchBase() {
    val work = BoardWork({ _, command, args ->
      git += (listOf(command.name()) + args).joinToString(" ")
      when (command.name()) {
        "config" -> GitOut(true, listOf("origin/main"))
        "merge-base" -> GitOut(true, listOf("abc123"))
        "diff" -> GitOut(true, listOf("diff --git a/x b/x", "+hello"))
        else -> GitOut(false, emptyList())
      }
    }) { it.run() }
    val card = BoardCard(wt, "feature", "feature", null)
    assertEquals("origin/main", work.baseOf(card))
    assertEquals("diff --git a/x b/x\n+hello", work.diff(wt, "origin/main"))
    assertTrue(git.toString(), "config --get branch.feature.base" in git && "merge-base origin/main HEAD" in git && "diff abc123" in git)
  }

  private fun all(c: java.awt.Component): List<java.awt.Component> =
    listOf(c) + ((c as? java.awt.Container)?.components?.flatMap { all(it) } ?: emptyList())
}

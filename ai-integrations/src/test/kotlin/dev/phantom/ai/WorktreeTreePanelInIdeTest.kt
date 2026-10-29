// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.SessionEntry
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.launch.LaunchCommand
import dev.phantom.ai.sidebar.PrInfo
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.ProjectOpener
import dev.phantom.ai.sidebar.ProjectRepos
import dev.phantom.ai.sidebar.ProjectSwitcher
import dev.phantom.ai.sidebar.RepoNode
import dev.phantom.ai.sidebar.SessionRow
import dev.phantom.ai.sidebar.SidebarActions
import dev.phantom.ai.sidebar.SidebarColors
import dev.phantom.ai.sidebar.Worktree
import dev.phantom.ai.sidebar.WorktreeNode
import dev.phantom.ai.sidebar.WorktreeSource
import dev.phantom.ai.sidebar.WorktreeTreePanel
import dev.phantom.ai.terminal.AgentTerminalService
import dev.phantom.ai.terminal.StartedTerminal
import dev.phantom.ai.terminal.TerminalStarter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** The sidebar of the current project in a real IDE: rows, menus, activation, no drag and drop. Fake repos, worktrees and starter. */
class WorktreeTreePanelInIdeTest : BasePlatformTestCase() {
  private class Started(val cwd: String, val command: String)

  private val started = mutableListOf<Started>()
  private val opened = mutableListOf<String>()
  private var roots = listOf("/r/app")
  private lateinit var panel: WorktreeTreePanel
  private lateinit var tree: JTree
  private var sessions = listOf<SessionEntry>()
  private var worktreeEntries = listOf<dev.phantom.ai.core.WorktreeEntry>()

  private val source = WorktreeSource { path ->
    if (path == "/r/app") listOf(Worktree("/r/app", "main", true), Worktree("/r/app-feat", "feat", false)) else emptyList()
  }
  private val prs = object : PrTitles {
    override fun titleFor(repoPath: String, branch: String): String? = if (branch == "feat") "Add feat" else null
    override fun infoFor(repoPath: String, branch: String) = if (branch == "feat") PrInfo(12, "Add feat") else null
  }

  override fun setUp() {
    super.setUp()
    val state = AiState.getInstance()
    sessions = state.state.sessions.toList()
    worktreeEntries = state.state.worktrees.toList()
    state.state.worktrees = mutableListOf()
    state.setActiveAccount(AgentKind.CLAUDE, null)
    state.setActiveAccount(AgentKind.CODEX, null)
    AgentTerminalService.getInstance(project).replaceStarter(TerminalStarter { _: Disposable, cwd, _, command ->
      started.add(Started(cwd, withoutMcp(command)))
      StartedTerminal(JPanel())
    })
    state.state.sessions = mutableListOf()
    SessionStore.getInstance().put(AgentSession("t1", "/r/app-feat", AgentKind.CLAUDE, sessionId = "sess-9", firstPrompt = "fix it"))
    panel = newPanel()
  }

  override fun tearDown() {
    try {
      AiState.getInstance().state.sessions = sessions.toMutableList()
      AiState.getInstance().state.worktrees = worktreeEntries.toMutableList()
      dev.phantom.ai.terminal.AgentTabsHost.getInstance(project).let { h -> h.tabs().forEach { h.close(it.id) } }
      SessionStore.getInstance().all().forEach { SessionStore.getInstance().remove(it.tabId) }
    } finally {
      super.tearDown()
    }
  }

  private fun newPanel(): WorktreeTreePanel {
    val p = WorktreeTreePanel(
      project, source, prs,
      SidebarActions(project, ProjectSwitcher { false }, ProjectOpener { opened.add(it) }),
      background = { it.run() },
      repos = ProjectRepos { roots },
    )
    Disposer.register(testRootDisposable, p)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    tree = p.treeForTest()
    return p
  }

  private fun objectAt(row: Int) = (tree.getPathForRow(row).lastPathComponent as DefaultMutableTreeNode).userObject

  private fun rowOf(match: (Any?) -> Boolean): Int = (0 until tree.rowCount).first { match(objectAt(it)) }

  private fun worktreeRow(path: String) = rowOf { it is WorktreeNode && it.path == path }
  private fun sessionRow() = rowOf { it is SessionRow }

  private fun click(row: Int, menu: String) {
    val item = panel.popupFor(row)!!.components.filterIsInstance<JMenuItem>().first { it.text == PhantomAiBundle.message(menu) }
    assertTrue("$menu enabled", item.isEnabled)
    item.doClick()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
  }

  fun testOneRepoShowsWorktreesAtTheTopWithTheirSessions() {
    val kinds = (0 until tree.rowCount).map { objectAt(it)!!::class.simpleName }
    assertEquals(listOf("WorktreeNode", "WorktreeNode", "SessionRow"), kinds)
    assertEquals("Add feat", (objectAt(worktreeRow("/r/app-feat")) as WorktreeNode).title)
    assertEquals(12, (objectAt(worktreeRow("/r/app-feat")) as WorktreeNode).prNumber)
    assertEquals("fix it", (objectAt(sessionRow()) as SessionRow).title)
    assertFalse(panel.showsEmptyText())
  }

  fun testSeveralReposShowRepoRows() {
    roots = listOf("/r/app", "/r/other")
    panel.refresh()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    val kinds = (0 until tree.rowCount).map { objectAt(it)!!::class.simpleName }
    assertEquals(listOf("RepoNode", "WorktreeNode", "WorktreeNode", "SessionRow", "RepoNode", "WorktreeNode"), kinds)
    assertEquals("app", (objectAt(0) as RepoNode).name)
  }

  fun testNoRepoShowsTheNoRepoText() {
    roots = emptyList()
    panel.refresh()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertTrue(panel.showsEmptyText())
    assertNull(panel.selectedWorktree())
  }

  fun testSelectedWorktreeFallsBackToMainAndFollowsSelection() {
    assertEquals("/r/app", panel.selectedWorktree())
    tree.selectionPath = tree.getPathForRow(sessionRow())
    assertEquals("/r/app-feat", panel.selectedWorktree())
    tree.selectionPath = tree.getPathForRow(worktreeRow("/r/app"))
    assertEquals("/r/app", panel.selectedWorktree())
  }

  fun testWorktreeMenuNewClaudeAndOpenInPhantomAndRename() {
    click(worktreeRow("/r/app-feat"), "action.new.claude")
    assertEquals("/r/app-feat", started.single().cwd)
    assertEquals(LaunchCommand.launch(AgentKind.CLAUDE), started.single().command)
    click(worktreeRow("/r/app-feat"), "sidebar.open.phantom")
    assertEquals(listOf("/r/app-feat"), opened)
    val previous = TestDialogManager.setTestInputDialog(TestInputDialog { "Renamed" })
    try {
      click(worktreeRow("/r/app-feat"), "sidebar.rename")
    } finally {
      TestDialogManager.setTestInputDialog(previous)
    }
    assertEquals("Renamed", (objectAt(worktreeRow("/r/app-feat")) as WorktreeNode).title)
  }

  fun testDoubleClickAndEnterOnSessionRowResume() {
    tree.selectionPath = tree.getPathForRow(sessionRow())
    tree.mouseListeners.forEach { it.mouseClicked(MouseEvent(tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 2, false)) }
    assertEquals(LaunchCommand.resume(AgentKind.CLAUDE, "sess-9"), started.single().command)
    tree.keyListeners.forEach { it.keyPressed(KeyEvent(tree, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n')) }
    assertEquals(2, started.size)
  }

  fun testSessionMenuResumeAndNoDragAndMockupColors() {
    click(sessionRow(), "action.resume")
    assertEquals(LaunchCommand.resume(AgentKind.CLAUDE, "sess-9"), started.single().command)
    assertFalse(tree.dragEnabled)
    assertNull(tree.transferHandler.takeIf { it.javaClass.name.contains("phantom") })
    assertEquals(0xe8b339, SidebarColors.WORKING.rgb and 0xffffff)
    assertEquals(0xe06c5a, SidebarColors.WAITING.rgb and 0xffffff)
    assertEquals(0x5fb865, SidebarColors.DONE.rgb and 0xffffff)
    assertEquals(0x8fb0ff, SidebarColors.PR_FG.rgb and 0xffffff)
    assertEquals(0x2c3550, SidebarColors.PR_BG.rgb and 0xffffff)
  }
}

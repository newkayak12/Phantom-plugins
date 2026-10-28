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
import dev.phantom.ai.core.GroupEntry
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.RepoEntry
import dev.phantom.ai.core.SessionEntry
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.launch.LaunchCommand
import dev.phantom.ai.sidebar.GroupEdits
import dev.phantom.ai.sidebar.GroupNode
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.ProjectGroupsPanel
import dev.phantom.ai.sidebar.ProjectOpener
import dev.phantom.ai.sidebar.ProjectSwitcher
import dev.phantom.ai.sidebar.RepoNode
import dev.phantom.ai.sidebar.SessionRow
import dev.phantom.ai.sidebar.SidebarActions
import dev.phantom.ai.sidebar.Worktree
import dev.phantom.ai.sidebar.WorktreeNode
import dev.phantom.ai.sidebar.WorktreeSource
import dev.phantom.ai.terminal.AgentTerminalService
import dev.phantom.ai.terminal.StartedTerminal
import dev.phantom.ai.terminal.TerminalStarter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.TransferHandler
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * The sidebar's Swing wiring in a real IDE: the tree rows, the context menus, drag and drop, the collapse state and the
 * session click. No real agent starts: the terminal starter is replaced by a recording one, and the project opener and
 * switcher are fakes.
 */
class ProjectGroupsPanelInIdeTest : BasePlatformTestCase() {
  private class Started(val cwd: String, val command: String)

  private val started = mutableListOf<Started>()
  private val opened = mutableListOf<String>()
  private lateinit var panel: ProjectGroupsPanel
  private lateinit var tree: JTree
  private var groups = listOf<GroupEntry>()
  private var repos = listOf<RepoEntry>()
  private var sessions = listOf<SessionEntry>()

  private val source = WorktreeSource { path ->
    if (path == "/r/app") listOf(Worktree("/r/app", "main", true), Worktree("/r/app-feat", "feat", false)) else emptyList()
  }

  override fun setUp() {
    super.setUp()
    val state = AiState.getInstance()
    groups = state.state.groups.toList()
    repos = state.state.repos.toList()
    sessions = state.state.sessions.toList()
    state.setActiveAccount(AgentKind.CLAUDE, null)
    state.setActiveAccount(AgentKind.CODEX, null)
    AgentTerminalService.getInstance(project).replaceStarter(TerminalStarter { _: Disposable, cwd, _, command ->
      started.add(Started(cwd, command))
      StartedTerminal(JPanel())
    })
    state.state.sessions = mutableListOf()
    var t = GroupEdits.addGroup(dev.phantom.ai.core.GroupTree(emptyList(), emptyList()), "Alpha", null, "g1")
    t = GroupEdits.addGroup(t, "Sub", "g1", "g3")
    t = GroupEdits.addGroup(t, "Beta", null, "g2")
    t = GroupEdits.addRepo(t, "/r/app", "g1")
    t = GroupEdits.addRepo(t, "/r/other", null)
    t = GroupEdits.addRepo(t, "/r/third", null)
    state.setTree(t)
    SessionStore.getInstance().put(AgentSession("t1", "/r/app-feat", AgentKind.CLAUDE, sessionId = "sess-9", firstPrompt = "fix it"))
    panel = newPanel()
  }

  override fun tearDown() {
    try {
      val state = AiState.getInstance()
      state.state.groups = groups.toMutableList()
      state.state.repos = repos.toMutableList()
      state.state.sessions = sessions.toMutableList()
      ToolWindowManager.getInstance(project).getToolWindow("AI Integrations")?.contentManager?.removeAllContents(true)
      SessionStore.getInstance().all().forEach { SessionStore.getInstance().remove(it.tabId) }
    } finally {
      super.tearDown()
    }
  }

  private fun newPanel(): ProjectGroupsPanel {
    val p = ProjectGroupsPanel(
      project, source, PrTitles { _, _ -> null },
      SidebarActions(project, ProjectSwitcher { false }, ProjectOpener { opened.add(it) }),
      background = { it.run() },
    )
    Disposer.register(testRootDisposable, p)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    tree = p.treeForTest()
    return p
  }

  private fun objectAt(row: Int) = (tree.getPathForRow(row).lastPathComponent as DefaultMutableTreeNode).userObject

  private fun rowOf(match: (Any?) -> Boolean): Int = (0 until tree.rowCount).first { match(objectAt(it)) }

  private fun groupRow(id: String) = rowOf { it is GroupNode && it.group.id == id }
  private fun repoRow(path: String) = rowOf { it is RepoNode && it.repo.path == path }
  private fun worktreeRow(path: String) = rowOf { it is WorktreeNode && it.path == path }
  private fun sessionRow() = rowOf { it is SessionRow }

  private fun click(row: Int, menu: String) {
    val item = panel.popupFor(row)!!.components.filterIsInstance<JMenuItem>().first { it.text == PhantomAiBundle.message(menu) }
    assertTrue("$menu enabled", item.isEnabled)
    item.doClick()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
  }

  fun testTreeShowsGroupRepoWorktreeSessionRows() {
    val kinds = (0 until tree.rowCount).map { objectAt(it)!!::class.simpleName }
    assertEquals(
      listOf(
        "GroupNode", "GroupNode", "RepoNode", "WorktreeNode", "WorktreeNode", "SessionRow",
        "GroupNode", "RepoNode", "WorktreeNode", "RepoNode", "WorktreeNode",
      ),
      kinds,
    )
    assertTrue(groupRow("g3") > groupRow("g1"))
    assertTrue(repoRow("/r/app") > groupRow("g1"))
    assertTrue(worktreeRow("/r/app-feat") > worktreeRow("/r/app"))
    assertEquals("fix it", (objectAt(sessionRow()) as SessionRow).title)
  }

  fun testWorktreeMenuNewClaudeSessionStartsTerminalInWorktree() {
    click(worktreeRow("/r/app-feat"), "action.new.claude")
    assertEquals("/r/app-feat", started.single().cwd)
    assertEquals(LaunchCommand.launch(AgentKind.CLAUDE), started.single().command)
    assertEquals("claude '--dangerously-skip-permissions'", started.single().command)
  }

  fun testWorktreeMenuOpenInPhantomUsesOpener() {
    click(worktreeRow("/r/app-feat"), "sidebar.open.phantom")
    assertEquals(listOf("/r/app-feat"), opened)
  }

  fun testRepoMenuRemoveRepositoryPersists() {
    click(repoRow("/r/other"), "sidebar.remove.repo")
    assertEquals(listOf("/r/app", "/r/third"), AiState.getInstance().tree().repos.map { it.path }.sorted())
    assertFalse((0 until tree.rowCount).any { (objectAt(it) as? RepoNode)?.repo?.path == "/r/other" })
  }

  fun testGroupMenuRenameUsesDialogAndPersists() {
    val previous = TestDialogManager.setTestInputDialog(TestInputDialog { "Renamed" })
    try {
      click(groupRow("g2"), "sidebar.rename")
    } finally {
      TestDialogManager.setTestInputDialog(previous)
    }
    assertEquals("Renamed", AiState.getInstance().tree().groups.first { it.id == "g2" }.name)
    assertEquals("Renamed", (objectAt(groupRow("g2")) as GroupNode).group.name)
  }

  fun testCollapsingAGroupIsPersistedAndRestoredInANewPanel() {
    val row = groupRow("g1")
    val path = tree.getPathForRow(row)
    assertTrue(tree.isExpanded(path))
    tree.collapsePath(path)
    assertTrue(AiState.getInstance().tree().groups.first { it.id == "g1" }.collapsed)
    assertFalse(AiState.getInstance().tree().groups.first { it.id == "g2" }.collapsed)

    val again = newPanel()
    assertSame(again.treeForTest(), tree)
    assertTrue(tree.isCollapsed(tree.getPathForRow(groupRow("g1"))))
    tree.expandPath(tree.getPathForRow(groupRow("g1")))
    assertFalse(AiState.getInstance().tree().groups.first { it.id == "g1" }.collapsed)
  }

  fun testDragRepoOntoGroupMovesIt() {
    val key = panel.dragKeyAt(repoRow("/r/other"))
    assertEquals("repo:/r/other", key)
    // The handler is the tree's own; importData ends in the extracted drop that is called here (TransferSupport has no public drop location).
    val handler = tree.transferHandler
    assertEquals(TransferHandler.MOVE, handler.getSourceActions(tree))
    assertTrue(panel.drop(key!!, tree.getPathForRow(groupRow("g2"))))
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertEquals("g2", AiState.getInstance().tree().repos.first { it.path == "/r/other" }.groupId)
    assertTrue(repoRow("/r/other") > groupRow("g2"))
  }

  fun testDragRepoOntoRepoReordersBefore() {
    assertEquals(listOf("/r/other", "/r/third"), AiState.getInstance().tree().reposOf(null).map { it.path })
    assertTrue(panel.drop("repo:/r/third", tree.getPathForRow(repoRow("/r/other"))))
    assertEquals(listOf("/r/third", "/r/other"), AiState.getInstance().tree().reposOf(null).map { it.path })
  }

  fun testDropGroupIntoItsOwnChildIsRejected() {
    val before = AiState.getInstance().tree().groups.map { Triple(it.id, it.parentId, it.order) }
    assertEquals("group:g1", panel.dragKeyAt(groupRow("g1")))
    assertFalse(panel.drop("group:g1", tree.getPathForRow(groupRow("g3"))))
    assertEquals(before, AiState.getInstance().tree().groups.map { Triple(it.id, it.parentId, it.order) })
    assertNull(AiState.getInstance().tree().groups.first { it.id == "g1" }.parentId)
  }

  fun testDoubleClickOnSessionRowResumesWithItsId() {
    tree.selectionPath = tree.getPathForRow(sessionRow())
    val e = MouseEvent(tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 2, false)
    tree.mouseListeners.forEach { it.mouseClicked(e) }
    assertEquals("/r/app-feat", started.single().cwd)
    assertEquals(LaunchCommand.resume(AgentKind.CLAUDE, "sess-9"), started.single().command)
    assertTrue(started.single().command.endsWith("'--resume' 'sess-9'"))
  }

  fun testEnterOnSessionRowActivates() {
    tree.selectionPath = tree.getPathForRow(sessionRow())
    val e = KeyEvent(tree, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n')
    tree.keyListeners.forEach { it.keyPressed(e) }
    assertEquals(LaunchCommand.resume(AgentKind.CLAUDE, "sess-9"), started.single().command)
  }

  fun testSessionRowMenuResumeIsEnabledWhenResumable() {
    click(sessionRow(), "action.resume")
    assertEquals(LaunchCommand.resume(AgentKind.CLAUDE, "sess-9"), started.single().command)
  }
}

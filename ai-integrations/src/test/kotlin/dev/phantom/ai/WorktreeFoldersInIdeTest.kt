// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.AiStateData
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.WorktreeFolderEntry
import dev.phantom.ai.core.addFolder
import dev.phantom.ai.core.deleteFolder
import dev.phantom.ai.core.folders
import dev.phantom.ai.core.moveToFolder
import dev.phantom.ai.core.renameFolder
import dev.phantom.ai.core.moveWorktree
import dev.phantom.ai.sidebar.FolderNode
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.ProjectOpener
import dev.phantom.ai.sidebar.ProjectRepos
import dev.phantom.ai.sidebar.ProjectSwitcher
import dev.phantom.ai.sidebar.RepoNode
import dev.phantom.ai.sidebar.SidebarActions
import dev.phantom.ai.sidebar.Worktree
import dev.phantom.ai.sidebar.WorktreeNode
import dev.phantom.ai.sidebar.WorktreeSource
import dev.phantom.ai.sidebar.WorktreeTreePanel
import javax.swing.JMenuItem
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** Folders of the worktree tree in a real IDE: made, renamed and deleted from menus, filled by drops, kept in AiState. */
class WorktreeFoldersInIdeTest : BasePlatformTestCase() {
  private var roots = listOf("/r/app")
  private lateinit var panel: WorktreeTreePanel
  private lateinit var tree: JTree
  private var folders = listOf<WorktreeFolderEntry>()
  private val source = WorktreeSource { path ->
    when (path) {
      "/r/app" -> listOf(Worktree("/r/app", "main", true), Worktree("/r/app-a", "a", false), Worktree("/r/app-b", "b", false))
      "/r/other" -> listOf(Worktree("/r/other", "main", true))
      else -> emptyList()
    }
  }
  private val prs = object : PrTitles {
    override fun titleFor(repoPath: String, branch: String): String? = null
    override fun infoFor(repoPath: String, branch: String) = null
  }

  override fun setUp() {
    super.setUp()
    folders = AiState.getInstance().state.worktreeFolders.toList()
    AiState.getInstance().state.worktreeFolders = mutableListOf()
    panel = WorktreeTreePanel(
      project, source, prs, SidebarActions(project, ProjectSwitcher { false }, ProjectOpener { }),
      background = { it.run() }, repos = ProjectRepos { roots },
    )
    Disposer.register(testRootDisposable, panel)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    tree = panel.treeForTest()
  }

  override fun tearDown() {
    try {
      AiState.getInstance().state.worktreeFolders = folders.toMutableList()
    } finally {
      super.tearDown()
    }
  }

  private fun objectAt(row: Int) = (tree.getPathForRow(row).lastPathComponent as DefaultMutableTreeNode).userObject

  private fun rows(): List<String> = (0 until tree.rowCount).map {
    when (val o = objectAt(it)) {
      is FolderNode -> "[" + o.name + "]"
      is WorktreeNode -> "  ".repeat(tree.getPathForRow(it).pathCount - 2) + o.path
      is RepoNode -> "repo " + o.path
      else -> o.toString()
    }
  }

  private fun rowOf(match: (Any?) -> Boolean): Int = (0 until tree.rowCount).first { match(objectAt(it)) }

  private fun folderRow(name: String) = rowOf { it is FolderNode && it.name == name }

  private fun click(row: Int, menu: String, input: String? = null) {
    val item = panel.popupFor(row)!!.components.filterIsInstance<JMenuItem>().first { it.text == PhantomAiBundle.message(menu) }
    val previous = TestDialogManager.setTestInputDialog(TestInputDialog { input })
    try {
      item.doClick()
    } finally {
      TestDialogManager.setTestInputDialog(previous)
    }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
  }

  private fun refreshed() {
    panel.refresh()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
  }

  fun testNewFolderDropInAndOutRenameAndDelete() {
    // below the last row of a single repository's tree
    click(-1, "sidebar.folder.new", "Review")
    assertEquals(listOf("[Review]", "/r/app", "/r/app-a", "/r/app-b"), rows())

    assertTrue(panel.dropForTest("/r/app-b", folderRow("Review")))
    refreshed()
    assertEquals(listOf("[Review]", "  /r/app-b", "/r/app", "/r/app-a"), rows())
    assertEquals(listOf("/r/app-b"), AiState.getInstance().folders("/r/app").single().worktrees)

    // a drop on a worktree in the folder goes into that folder
    assertTrue(panel.dropForTest("/r/app-a", rowOf { it is WorktreeNode && it.path == "/r/app-b" }))
    refreshed()
    assertEquals(listOf("[Review]", "  /r/app-a", "  /r/app-b", "/r/app"), rows())

    // out again: onto a top-level row, or below the last row
    assertTrue(panel.dropForTest("/r/app-a", rowOf { it is WorktreeNode && it.path == "/r/app" }))
    assertTrue(panel.dropForTest("/r/app-b", -1))
    refreshed()
    assertEquals(listOf("[Review]", "/r/app", "/r/app-a", "/r/app-b"), rows())

    panel.dropForTest("/r/app-a", folderRow("Review"))
    refreshed()
    click(folderRow("Review"), "sidebar.folder.rename", "Done")
    assertEquals(listOf("[Done]", "  /r/app-a", "/r/app", "/r/app-b"), rows())

    click(folderRow("Done"), "sidebar.folder.delete")
    assertEquals(listOf("/r/app", "/r/app-a", "/r/app-b"), rows())
    assertTrue(AiState.getInstance().folders("/r/app").isEmpty())
  }

  fun testAWorktreeCannotBeDroppedIntoAnotherRepository() {
    roots = listOf("/r/app", "/r/other")
    refreshed()
    click(rowOf { it is RepoNode && it.path == "/r/other" }, "sidebar.folder.new", "Theirs")
    assertFalse(panel.dropForTest("/r/app-a", folderRow("Theirs")))
    assertFalse(panel.dropForTest("/r/app-a", rowOf { it is RepoNode && it.path == "/r/other" }))
    assertTrue(AiState.getInstance().folders("/r/other").single().worktrees.isEmpty())
  }

  fun testTheTreeCanDragWorktrees() {
    assertTrue(tree.dragEnabled)
    assertEquals(javax.swing.DropMode.ON, tree.dropMode)
  }

  fun testFoldersAreKeptPerRepositoryAndSurviveARestart() {
    val a = AiState()
    assertTrue(a.addFolder("/r", "Review"))
    assertFalse(a.addFolder("/r", "Review"))
    assertTrue(a.addFolder("/s", "Review"))
    a.moveToFolder("/r", "/r/a", "Review")
    a.moveToFolder("/r", "/r/b", "Review")
    val b = AiState()
    b.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(a.state), AiStateData::class.java))
    assertEquals(listOf("Review"), b.folders("/r").map { it.name })
    assertEquals(listOf("/r/a", "/r/b"), b.folders("/r").single().worktrees)
    assertEquals(emptyList<String>(), b.folders("/s").single().worktrees)
  }

  fun testMovingOutRenamingAndDeletingAFolder() {
    val a = AiState()
    a.addFolder("/r", "X")
    a.addFolder("/r", "Y")
    a.moveToFolder("/r", "/r/a", "X")
    a.moveToFolder("/r", "/r/a", "Y")
    assertEquals(emptyList<String>(), a.folders("/r").first { it.name == "X" }.worktrees)
    assertFalse(a.renameFolder("/r", "Y", "X"))
    assertTrue(a.renameFolder("/r", "Y", "Z"))
    assertEquals(listOf("/r/a"), a.folders("/r").first { it.name == "Z" }.worktrees)
    a.moveToFolder("/r", "/r/a", null)
    assertTrue(a.folders("/r").all { it.worktrees.isEmpty() })
    a.moveToFolder("/r", "/r/a", "Z")
    a.deleteFolder("/r", "Z")
    assertEquals(listOf("X"), a.folders("/r").map { it.name })
  }

  fun testAMovedWorktreeKeepsItsFolderLineageAndSessionsAndShowsItsNewName() {
    val a = AiState()
    a.addFolder("/r", "X")
    a.moveToFolder("/r", "/r/a", "X")
    a.worktree("/r/child").parentPath = "/r/a"
    a.rememberSession("/r/a", AgentKind.CLAUDE, "s1", "hi")
    a.moveWorktree("/r/a", "/r/login", "login")
    assertEquals("login", a.findWorktree("/r/login")!!.userName)
    assertNull(a.findWorktree("/r/a"))
    assertEquals("/r/login", a.findWorktree("/r/child")!!.parentPath)
    assertEquals("hi", a.sessionsOf("/r/login").single().firstPrompt)
    assertEquals(listOf("/r/login"), a.folders("/r").single().worktrees)
  } }

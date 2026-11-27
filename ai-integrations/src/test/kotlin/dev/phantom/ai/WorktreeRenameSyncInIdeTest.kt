// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.core.WorktreeEntry
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.ProjectOpener
import dev.phantom.ai.sidebar.ProjectRepos
import dev.phantom.ai.sidebar.ProjectSwitcher
import dev.phantom.ai.sidebar.SidebarActions
import dev.phantom.ai.sidebar.WorktreeNode
import dev.phantom.ai.sidebar.WorktreePorcelain
import dev.phantom.ai.sidebar.WorktreeSource
import dev.phantom.ai.sidebar.WorktreeTreePanel
import dev.phantom.workspaces.core.WorktreeRename
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JMenuItem
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * A worktree renamed in this tree or in Phantom Workspaces' Projects and Files list (WorktreeRename) is the same folder
 * move with real git, and both show the new name.
 */
class WorktreeRenameSyncInIdeTest : BasePlatformTestCase() {
  private lateinit var root: Path
  private lateinit var repo: Path
  private lateinit var panel: WorktreeTreePanel
  private lateinit var tree: JTree
  private var entries = listOf<WorktreeEntry>()

  // what git lists now, as the real source does
  private val source = WorktreeSource { path ->
    val process = ProcessBuilder("git", "worktree", "list", "--porcelain").directory(Path.of(path).toFile()).start()
    WorktreePorcelain.parse(process.inputStream.bufferedReader().readLines()).also { process.waitFor() }
  }
  private val prs = object : PrTitles {
    override fun titleFor(repoPath: String, branch: String): String? = null
    override fun infoFor(repoPath: String, branch: String) = null
  }

  private fun git(dir: Path, vararg args: String) {
    val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + args)
      .directory(dir.toFile()).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(output, 0, process.waitFor())
  }

  override fun setUp() {
    super.setUp()
    entries = AiState.getInstance().state.worktrees.toList()
    AiState.getInstance().state.worktrees = mutableListOf()
    root = FileUtil.createTempDirectory("sync", null).toPath().toRealPath()
    repo = Files.createDirectories(root.resolve("app"))
    git(repo, "init", "-q", "-b", "main")
    Files.writeString(repo.resolve("a.txt"), "a")
    git(repo, "add", ".")
    git(repo, "commit", "-q", "-m", "init")
    git(repo, "worktree", "add", "-q", "-b", "feature", root.resolve("feature").toString())
    panel = WorktreeTreePanel(
      project, source, prs, SidebarActions(project, ProjectSwitcher { false }, ProjectOpener { }),
      background = { it.run() }, repos = ProjectRepos { listOf(repo.toString()) },
    )
    Disposer.register(testRootDisposable, panel)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    tree = panel.treeForTest()
  }

  override fun tearDown() {
    try {
      AiState.getInstance().state.worktrees = entries.toMutableList()
      SessionStore.getInstance().all().forEach { SessionStore.getInstance().remove(it.tabId) }
    } finally {
      super.tearDown()
    }
  }

  private fun worktrees(): List<WorktreeNode> =
    (0 until tree.rowCount).mapNotNull { (tree.getPathForRow(it).lastPathComponent as DefaultMutableTreeNode).userObject as? WorktreeNode }

  private fun renameInTree(path: Path, name: String) {
    val row = (0 until tree.rowCount).first { ((tree.getPathForRow(it).lastPathComponent as DefaultMutableTreeNode).userObject as? WorktreeNode)?.path == path.toString() }
    val item = panel.popupFor(row)!!.components.filterIsInstance<JMenuItem>().first { it.text == PhantomAiBundle.message("sidebar.rename") }
    val previous = TestDialogManager.setTestInputDialog(TestInputDialog { name })
    try {
      item.doClick()
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    } finally {
      TestDialogManager.setTestInputDialog(previous)
    }
  }

  fun testRenamingInTheTreeMovesTheFolderAndShowsTheNewName() {
    renameInTree(root.resolve("feature"), "login")
    assertFalse(Files.exists(root.resolve("feature")))
    assertTrue(Files.isRegularFile(root.resolve("login/a.txt")))
    val shown = worktrees().single { !it.isMain }
    assertEquals(root.resolve("login").toString(), shown.path)
    assertEquals("login", shown.title)
    assertEquals("feature", shown.branch)
  }

  fun testRenamingInProjectsAndFilesShowsTheNewNameHere() {
    AiState.getInstance().worktree(root.resolve("feature").toString()).userName = "Old name"
    val previous = TestDialogManager.setTestInputDialog(TestInputDialog { "signup" })
    try {
      // what the Projects and Files list's Rename… runs
      WorktreeRename.rename(project, root.resolve("feature").toString(), { it.run() })
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    } finally {
      TestDialogManager.setTestInputDialog(previous)
    }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertTrue(Files.isDirectory(root.resolve("signup")))
    val shown = worktrees().single { !it.isMain }
    assertEquals(root.resolve("signup").toString(), shown.path)
    assertEquals("signup", shown.title)
    assertNull(AiState.getInstance().findWorktree(root.resolve("feature").toString()))
  }

  fun testAWorktreeWithAgentSessionsIsNotRenamed() {
    SessionStore.getInstance().put(AgentSession("t1", root.resolve("feature").toString(), AgentKind.CLAUDE))
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    var message: String? = null
    val previous = TestDialogManager.setTestDialog(TestDialog { message = it; 0 })
    try {
      renameInTree(root.resolve("feature"), "login")
    } finally {
      TestDialogManager.setTestDialog(previous)
    }
    assertEquals(PhantomAiBundle.message("worktree.rename.busy"), message)
    assertTrue(Files.isDirectory(root.resolve("feature")))
  }
}

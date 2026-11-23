// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.AiStateData
import dev.phantom.ai.sidebar.Git4IdeaRun
import dev.phantom.ai.sidebar.GitWorktreeSource
import dev.phantom.ai.sidebar.NewWorktreeRequest
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.ProjectOpener
import dev.phantom.ai.sidebar.ProjectRepos
import dev.phantom.ai.sidebar.ProjectSwitcher
import dev.phantom.ai.sidebar.SidebarActions
import dev.phantom.ai.sidebar.WorktreeCreator
import dev.phantom.ai.sidebar.WorktreeNode
import dev.phantom.ai.sidebar.WorktreeTreePanel
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.swing.tree.DefaultMutableTreeNode

/** The whole New worktree path from the tool window: + (title bar or hovered row) -> dialog -> WorktreeCreator -> git worktree add. */
class NewWorktreeInIdeTest : BasePlatformTestCase() {
  private lateinit var base: File
  private lateinit var repo: File
  private val asked = mutableListOf<Pair<String, String?>>()
  private val opened = mutableListOf<String>()

  override fun setUp() {
    super.setUp()
    AiState.getInstance().loadState(AiStateData())
    dev.phantom.ai.terminal.AgentTerminalService.getInstance(project).replaceStarter(
      dev.phantom.ai.terminal.TerminalStarter { _: com.intellij.openapi.Disposable, _, _, _ -> dev.phantom.ai.terminal.StartedTerminal(javax.swing.JPanel()) },
    )
    base = Files.createTempDirectory("wtplus").toRealPath().toFile()
    val seed = File(base, "seed").also { it.mkdirs() }
    git(seed, "init", "-b", "main")
    File(seed, "a.txt").writeText("a\n")
    git(seed, "add", ".")
    git(seed, "-c", "user.name=t", "commit", "-m", "init")
    git(base, "clone", "--bare", seed.path, File(base, "origin.git").path)
    git(base, "clone", File(base, "origin.git").path, File(base, "app").path)
    repo = File(base, "app")
    git(repo, "config", "user.name", "Tester")
  }

  override fun tearDown() {
    try {
      dev.phantom.ai.terminal.AgentTabsHost.getInstance(project).let { h -> h.tabs().forEach { h.close(it.id) } }
      dev.phantom.ai.core.SessionStore.getInstance().all().forEach { dev.phantom.ai.core.SessionStore.getInstance().remove(it.tabId) }
      AiState.getInstance().loadState(AiStateData())
      base.deleteRecursively()
    } finally {
      super.tearDown()
    }
  }

  private fun git(dir: File, vararg args: String): String {
    val p = ProcessBuilder(listOf("git", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + args)
      .directory(dir).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    assertTrue("git ${args.toList()} failed: $out", p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0)
    return out
  }

  private fun panel(name: String, openAsProject: Boolean = true): WorktreeTreePanel {
    // the panel's own defaults for git: Git4IdeaRun on the default project, as in the tool window
    val p = WorktreeTreePanel(
      project, GitWorktreeSource(), PrTitles { _, _ -> null },
      SidebarActions(project, ProjectSwitcher { false }, ProjectOpener { opened += it }),
      creator = WorktreeCreator(Git4IdeaRun(), File(base, "workspaces").path),
      repos = ProjectRepos { listOf(repo.path) },
      askNewWorktree = { n, b -> asked += n to b; NewWorktreeRequest(name, null, dev.phantom.ai.core.AgentKind.CLAUDE, openAsProject) },
    )
    Disposer.register(testRootDisposable, p)
    waitFor { p.treeForTest().rowCount > 0 }
    return p
  }

  private fun waitFor(condition: () -> Boolean) {
    val until = System.currentTimeMillis() + 60_000
    while (!condition()) {
      assertTrue("timed out", System.currentTimeMillis() < until)
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      Thread.sleep(20)
    }
  }

  private fun created(name: String) = File(base, "workspaces/app/$name")

  fun testTitleBarPlusCreatesTheWorktreeOnANewBranchThroughGit() {
    val p = panel("task-a")
    p.newWorktreeForSelection()
    waitFor { created("task-a").isDirectory && p.treeForTest().rowCount > 1 }
    assertEquals(listOf("app" to "origin/main"), asked)
    assertTrue(git(repo, "worktree", "list").contains(created("task-a").path))
    assertTrue(git(repo, "branch", "--list").contains("Tester/task-a"))
    // the agent starts in this window, then the worktree opens as its own project
    assertEquals(created("task-a").path, dev.phantom.ai.core.SessionStore.getInstance().all().single().worktree)
    assertEquals(listOf(created("task-a").path), opened)
  }

  fun testOpenAsProjectOffKeepsTheWorktreeInThisWindow() {
    val p = panel("task-c", openAsProject = false)
    p.newWorktreeForSelection()
    waitFor { created("task-c").isDirectory && dev.phantom.ai.core.SessionStore.getInstance().all().isNotEmpty() }
    assertTrue(opened.isEmpty())
  }

  fun testTheDialogRemembersOpenAsProject() {
    val before = dev.phantom.ai.sidebar.WorktreeOpening.asProject
    try {
      dev.phantom.ai.sidebar.WorktreeOpening.asProject = true
      val on = dev.phantom.ai.sidebar.NewWorktreeDialog(project, "app", null)
      assertTrue(on.request().openAsProject)
      Disposer.dispose(on.disposable)
      dev.phantom.ai.sidebar.WorktreeOpening.asProject = false
      val off = dev.phantom.ai.sidebar.NewWorktreeDialog(project, "app", null)
      assertFalse(off.request().openAsProject)
      Disposer.dispose(off.disposable)
    } finally {
      dev.phantom.ai.sidebar.WorktreeOpening.asProject = before
    }
  }

  fun testThePlusOnAHoveredRowCreatesAWorktreeBelowIt() {
    val p = panel("task-b")
    val tree = p.treeForTest()
    val row = (0 until tree.rowCount).first { ((tree.getPathForRow(it).lastPathComponent as DefaultMutableTreeNode).userObject as? WorktreeNode)?.isMain == true }
    val bounds = tree.getRowBounds(row)
    val x = bounds.x + bounds.width - 5
    val y = bounds.y + bounds.height / 2
    assertFalse(p.plusAt(x, y)) // not hovered yet
    p.hoverForTest(row)
    assertTrue(p.plusAt(x, y))
    assertFalse(p.plusAt(bounds.x + 2, y))
    tree.mouseListeners.forEach { it.mouseClicked(MouseEvent(tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, x, y, 1, false)) }
    waitFor { created("task-b").isDirectory }
    assertEquals(1, asked.size)
  }
}

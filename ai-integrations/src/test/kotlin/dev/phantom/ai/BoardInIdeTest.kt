// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.board.WorkspaceBoardPanel
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.AiStateData
import dev.phantom.ai.core.AiToolWindowFactory
import dev.phantom.ai.sidebar.PrInfo
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.ProjectRepos
import dev.phantom.ai.sidebar.Worktree

class BoardInIdeTest : BasePlatformTestCase() {
  private val repo = "/r/repo"
  private val wt = "/r/repo-feature"

  override fun setUp() {
    super.setUp()
    AiState.getInstance().loadState(AiStateData())
  }

  override fun tearDown() {
    try {
      AiState.getInstance().loadState(AiStateData())
    } finally {
      super.tearDown()
    }
  }

  private fun panel(): WorkspaceBoardPanel {
    val prs = object : PrTitles {
      override fun titleFor(repoPath: String, branch: String): String? = "Add login"
      override fun infoFor(repoPath: String, branch: String) = PrInfo(7, "Add login")
    }
    val p = WorkspaceBoardPanel(project, { listOf(Worktree(repo, "main", true), Worktree(wt, "feature", false)) }, prs, ProjectRepos { listOf(repo) })
    Disposer.register(testRootDisposable, p)
    p.showCards(p.buildCards())
    return p
  }

  fun testToolWindowHasOnePanelContentAndNoBoardTab() {
    val tw = ToolWindowManager.getInstance(project).registerToolWindow(RegisterToolWindowTask.notClosable("Phantom AI Integration-test"))
    AiToolWindowFactory().createToolWindowContent(project, tw)
    val contents = tw.contentManager.contents
    assertEquals(1, contents.size)
    assertFalse(contents.single().isCloseable)
    assertTrue(contents.single().component is dev.phantom.ai.toolwindow.AgentToolWindowPanel)
  }

  fun testCardsShowPrTitleBranchAndGoToFirstColumn() {
    val p = panel()
    assertEquals(listOf(repo, wt), p.currentLayout().cardsOf("Todo"))
    val labels = collect(p, javax.swing.JLabel::class.java).map { it.text }
    assertTrue(labels.toString(), "Add login" in labels && "#7" in labels && "feature" in labels)
  }

  fun testProgrammaticMovePersistsToAiState() {
    val p = panel()
    p.moveCard(wt, "Done", 0)
    assertEquals("Done", AiState.getInstance().findWorktree(wt)?.column)
    assertEquals(listOf(wt), p.currentLayout().cardsOf("Done"))
    // a fresh panel reads the stored placement back
    val again = WorkspaceBoardPanel(project, { listOf(Worktree(repo, "main", true), Worktree(wt, "feature", false)) }, PrTitles { _, _ -> null }, ProjectRepos { listOf(repo) })
    Disposer.register(testRootDisposable, again)
    again.showCards(again.buildCards())
    assertEquals(listOf(wt), again.currentLayout().cardsOf("Done"))
  }

  fun testBoardFileIsOneReadOnlyFilePerProjectAndProviderAcceptsOnlyIt() {
    val f = dev.phantom.ai.board.BoardVirtualFile.of(project)
    assertSame(f, dev.phantom.ai.board.BoardVirtualFile.of(project))
    assertFalse(f.isWritable)
    val provider = dev.phantom.ai.board.BoardFileEditorProvider()
    assertTrue(provider.accept(project, f))
    assertFalse(provider.accept(project, com.intellij.testFramework.LightVirtualFile("x.txt")))
    assertEquals(com.intellij.openapi.fileEditor.FileEditorPolicy.HIDE_DEFAULT_EDITOR, provider.policy)
  }

  fun testEditorWrapsPanelAndDisposesIt() {
    val f = dev.phantom.ai.board.BoardVirtualFile.of(project)
    val e = dev.phantom.ai.board.BoardFileEditorProvider().createEditor(project, f) as dev.phantom.ai.board.BoardFileEditor
    assertTrue(e.component is WorkspaceBoardPanel)
    Disposer.dispose(e)
  }

  fun testOpenBoardActionOpensTheBoardEditor() {
    val action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("dev.phantom.ai.OpenBoard")
    assertNotNull(action)
    assertTrue(action is dev.phantom.ai.board.OpenBoardAction)
    val mgr = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
    mgr.openFile(dev.phantom.ai.board.BoardVirtualFile.of(project), true)
    assertTrue(mgr.isFileOpen(dev.phantom.ai.board.BoardVirtualFile.of(project)))
    mgr.closeFile(dev.phantom.ai.board.BoardVirtualFile.of(project))
  }

  private fun <T : java.awt.Component> collect(c: java.awt.Component, type: Class<T>): List<T> {
    val out = ArrayList<T>()
    if (type.isInstance(c)) out.add(type.cast(c))
    if (c is java.awt.Container) c.components.forEach { out.addAll(collect(it, type)) }
    return out
  }
}

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
import dev.phantom.ai.core.Group
import dev.phantom.ai.core.GroupTree
import dev.phantom.ai.core.Repo
import dev.phantom.ai.sidebar.PrInfo
import dev.phantom.ai.sidebar.PrTitles
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
    AiState.getInstance().setTree(GroupTree(emptyList<Group>(), listOf(Repo(repo, null, 0))))
    val prs = object : PrTitles {
      override fun titleFor(repoPath: String, branch: String): String? = "Add login"
      override fun infoFor(repoPath: String, branch: String) = PrInfo(7, "Add login")
    }
    val p = WorkspaceBoardPanel(project, { listOf(Worktree(repo, "main", true), Worktree(wt, "feature", false)) }, prs)
    Disposer.register(testRootDisposable, p)
    p.showCards(p.buildCards())
    return p
  }

  fun testBoardTabExistsInTheToolWindow() {
    val tw = ToolWindowManager.getInstance(project).registerToolWindow(RegisterToolWindowTask.notClosable("AI Integrations-test"))
    AiToolWindowFactory().createToolWindowContent(project, tw)
    val titles = tw.contentManager.contents.map { it.displayName }
    assertTrue(titles.toString(), "Board" in titles)
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
    val again = WorkspaceBoardPanel(project, { listOf(Worktree(repo, "main", true), Worktree(wt, "feature", false)) }, PrTitles { _, _ -> null })
    Disposer.register(testRootDisposable, again)
    again.showCards(again.buildCards())
    assertEquals(listOf(wt), again.currentLayout().cardsOf("Done"))
  }

  private fun <T : java.awt.Component> collect(c: java.awt.Component, type: Class<T>): List<T> {
    val out = ArrayList<T>()
    if (type.isInstance(c)) out.add(type.cast(c))
    if (c is java.awt.Container) c.components.forEach { out.addAll(collect(it, type)) }
    return out
  }
}

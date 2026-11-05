// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.phantom.ai.memo.AiMemoStore
import dev.phantom.ai.memo.MemoEditorBlocks

/** The add action, the input block and one saved block per memo in the editor. */
class MemoEditorInIdeTest : BasePlatformTestCase() {
  private lateinit var store: AiMemoStore

  override fun setUp() {
    super.setUp()
    // light fixture files live under /src (temp:///src/...): that is the worktree here
    store = AiMemoStore(project) { "/src" }
    Disposer.register(testRootDisposable, store)
    project.registerOrReplaceServiceInstance(AiMemoStore::class.java, store, testRootDisposable)
  }

  private val text = (0..9).joinToString("\n") { "line $it" }

  private fun blocks(editor: Editor = myFixture.editor) = requireNotNull(MemoEditorBlocks.of(editor)) { "no memo blocks on the editor" }

  private fun runAdd() = myFixture.testAction(ActionManager.getInstance().getAction("dev.phantom.ai.AddAiMemo"))

  fun testActionWithSelectionOpensInputForThoseLines() {
    myFixture.configureByText("A.java", text)
    val doc = myFixture.editor.document
    myFixture.editor.selectionModel.setSelection(doc.getLineStartOffset(2), doc.getLineEndOffset(4))
    runAdd()
    assertTrue(blocks().inputOpen())
    blocks().submitInput("fix these")
    assertFalse(blocks().inputOpen())
    val memo = store.all().single()
    assertEquals(2, memo.startLine)
    assertEquals(4, memo.endLine)
    assertEquals("fix these", memo.text)
  }

  fun testActionWithoutSelectionUsesCaretLine() {
    myFixture.configureByText("A.java", text)
    myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.getLineStartOffset(6) + 2)
    runAdd()
    assertTrue(blocks().inputOpen())
    blocks().submitInput("one line")
    val memo = store.all().single()
    assertEquals(6, memo.startLine)
    assertEquals(6, memo.endLine)
  }

  fun testSaveAndSendRunsTheSendActionWhenRegistered() {
    myFixture.configureByText("A.java", text)
    blocks().openInput(1, 1)
    blocks().submitInput("no send action yet", send = true) // not registered: only saved
    assertEquals(1, store.all().size)
    var sent = 0
    val manager = ActionManager.getInstance()
    manager.registerAction(MemoEditorBlocks.SEND_ACTION_ID, object : AnAction() {
      override fun actionPerformed(e: AnActionEvent) {
        sent++
      }
    })
    try {
      blocks().openInput(3, 3)
      blocks().submitInput("then send", send = true)
      assertEquals(2, store.all().size)
      assertEquals(1, sent)
    } finally {
      manager.unregisterAction(MemoEditorBlocks.SEND_ACTION_ID)
    }
  }

  fun testAddActionDisabledOutsideWorktree() {
    myFixture.openFileInEditor(LightVirtualFile("Out.txt", text))
    assertFalse(runAdd().isEnabled)
    myFixture.configureByText("A.java", text)
    assertTrue(runAdd().isEnabled)
  }

  fun testOneBlockPerMemo() {
    myFixture.configureByText("A.java", text)
    val doc = myFixture.editor.document
    val a = store.add(doc, 1, 1, "a")
    store.add(doc, 3, 5, "b")
    assertEquals(2, blocks().blockCount())
    store.remove(listOf(a.id))
    assertEquals(1, blocks().blockCount())
  }

  fun testBlocksComeBackWhenFileReopens() {
    myFixture.configureByText("A.java", text)
    val doc = myFixture.editor.document
    store.add(doc, 2, 3, "keep")
    val factory = EditorFactory.getInstance()
    val first = factory.createEditor(doc, project, EditorKind.MAIN_EDITOR)
    assertEquals(1, blocks(first).blockCount())
    factory.releaseEditor(first)
    assertNull(MemoEditorBlocks.of(first))
    val again = factory.createEditor(doc, project, EditorKind.MAIN_EDITOR)
    try {
      assertEquals(1, blocks(again).blockCount())
    } finally {
      factory.releaseEditor(again)
    }
  }
}

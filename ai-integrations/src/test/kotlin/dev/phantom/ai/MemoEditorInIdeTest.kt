// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.icons.AllIcons
import java.awt.Component
import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.keymap.KeymapManager
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.KeyStroke
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
    val real = manager.getAction(MemoEditorBlocks.SEND_ACTION_ID)
    manager.unregisterAction(MemoEditorBlocks.SEND_ACTION_ID)
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
      if (real != null) manager.registerAction(MemoEditorBlocks.SEND_ACTION_ID, real)
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

  fun testEditedMemoComesBackWhenAnotherInputOpens() {
    myFixture.configureByText("A.java", text)
    val doc = myFixture.editor.document
    store.add(doc, 1, 1, "x")
    store.add(doc, 4, 4, "y")
    assertEquals(2, blocks().blockCount())
    blocks().editInput(store.all().first())
    assertEquals(1, blocks().blockCount()) // x gives way to its input
    blocks().openInput(7, 7)
    assertEquals(2, blocks().blockCount())
    assertTrue(blocks().inputOpen())
  }

  fun testGutterPlusLeavesTheIconAreaAlone() {
    myFixture.configureByText("A.java", text)
    val editor = myFixture.editor as EditorEx
    editor.markupModel.addLineHighlighter(3, 0, null).gutterIconRenderer = object : GutterIconRenderer() {
      override fun getIcon() = AllIcons.Gutter.ImplementedMethod
      override fun equals(other: Any?) = other === this
      override fun hashCode() = 0
    }
    val gutter = editor.gutterComponentEx
    assertTrue("the + has its own slot", blocks().plusX() + AllIcons.General.InlineAdd.iconWidth <= gutter.iconAreaOffset)
    gutterClick(3, gutter.iconAreaOffset + 2) // on the line's icon
    assertFalse(blocks().inputOpen())
    gutterClick(5, gutter.iconAreaOffset + 2) // icon area of a plain line
    assertFalse(blocks().inputOpen())
    gutterClick(5, blocks().plusX() + 2)
    assertTrue(blocks().inputOpen())
    blocks().submitInput("from the gutter")
    assertEquals(5, store.all().single().startLine)
  }

  fun testKeysInTheInputGoToTheFieldNotTheCode() {
    myFixture.configureByText("A.java", text)
    val code = myFixture.editor.document.text
    myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.getLineStartOffset(5) + 3)
    val caret = myFixture.editor.caretModel.offset
    // the component-hierarchy data context the IDE uses, not the test one (which always answers the fixture's editor)
    HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
    (DataManager.getInstance() as HeadlessDataManager).setTestDataProvider({ null }, testRootDisposable)
    blocks().openInput(1, 1)
    val focus = blocks().inputFocus()
    "abc".forEach { type(focus, it) }
    press(focus, KeyEvent.VK_BACK_SPACE)
    press(focus, KeyEvent.VK_LEFT)
    press(focus, KeyEvent.VK_DELETE)
    assertEquals("a", blocks().inputText())
    val selectAll = KeymapManager.getInstance().activeKeymap.getShortcuts("\$SelectAll").filterIsInstance<KeyboardShortcut>().first().firstKeyStroke
    press(focus, selectAll.keyCode, selectAll.modifiers)
    press(focus, KeyEvent.VK_BACK_SPACE)
    assertEquals("", blocks().inputText())
    "ok".forEach { type(focus, it) }
    press(focus, KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)
    type(focus, '!')
    assertEquals("ok\n!", blocks().inputText())
    assertEquals(code, myFixture.editor.document.text)
    assertEquals(caret, myFixture.editor.caretModel.offset)
    press(focus, KeyEvent.VK_ENTER) // ↩ saves
    assertFalse(blocks().inputOpen())
    assertEquals("ok\n!", store.all().single().text)
    assertEquals(code, myFixture.editor.document.text)
  }

  private fun gutterClick(line: Int, x: Int) {
    val editor = myFixture.editor as EditorEx
    val gutter = editor.gutterComponentEx
    val y = editor.logicalPositionToXY(LogicalPosition(line, 0)).y + editor.lineHeight / 2
    for (id in listOf(MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
      val mods = if (id == MouseEvent.MOUSE_PRESSED) InputEvent.BUTTON1_DOWN_MASK else 0
      val button = if (id == MouseEvent.MOUSE_MOVED) MouseEvent.NOBUTTON else MouseEvent.BUTTON1
      gutter.dispatchEvent(MouseEvent(gutter, id, System.currentTimeMillis(), mods, x, y, 1, false, button))
    }
  }

  /**
   * A key pressed in [focus] the way the IDE routes it (headless tests have no focus owner, so IdeKeyEventDispatcher cannot be
   * driven directly): first the actions registered on the focus owner and its parents, then the keymap's actions for the stroke,
   * each updated in the focus owner's data context and the first enabled one performed, as IdeKeyEventDispatcher does; only if none
   * is enabled does the stroke reach the component's own Swing bindings.
   */
  private fun press(focus: JComponent, code: Int, modifiers: Int = 0) {
    val stroke = KeyStroke.getKeyStroke(code, modifiers)
    val keyEvent = KeyEvent(focus, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED)
    val shortcut = KeyboardShortcut(stroke, null)
    val local = generateSequence<Component>(focus) { it.parent }.filterIsInstance<JComponent>()
      .flatMap { ActionUtil.getActions(it).asSequence() }.filter { shortcut in it.shortcutSet.shortcuts }
    val keymap = KeymapManager.getInstance().activeKeymap.getActionIds(stroke).asSequence().mapNotNull { ActionManager.getInstance().getAction(it) }
    val context = DataManager.getInstance().getDataContext(focus)
    for (action in local + keymap) {
      val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.KEYBOARD_SHORTCUT, ActionUiKind.NONE, keyEvent)
      ActionUtil.updateAction(action, event)
      if (event.presentation.isEnabled) {
        ActionUtil.performAction(action, event)
        return
      }
    }
    val name = focus.getInputMap(JComponent.WHEN_FOCUSED).get(stroke) ?: return
    focus.actionMap.get(name)?.actionPerformed(ActionEvent(focus, ActionEvent.ACTION_PERFORMED, null))
  }

  /** A typed character: an editor's content component hands it to the TypedAction of the editor in its data context. */
  private fun type(focus: JComponent, ch: Char) {
    val context = DataManager.getInstance().getDataContext(focus)
    val editor = requireNotNull(CommonDataKeys.EDITOR.getData(context))
    assertNotSame("typing goes to the code editor", myFixture.editor, editor)
    TypedAction.getInstance().actionPerformed(editor, ch, context)
  }
}

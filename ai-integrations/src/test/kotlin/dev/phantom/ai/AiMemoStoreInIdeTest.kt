// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.memo.AiMemoListener
import dev.phantom.ai.memo.AiMemoStore

/** Memos follow their code through edits and go away with it. */
class AiMemoStoreInIdeTest : BasePlatformTestCase() {
  private lateinit var store: AiMemoStore
  private var changes = 0

  override fun setUp() {
    super.setUp()
    store = AiMemoStore(project) { "/src" }
    Disposer.register(testRootDisposable, store)
    project.messageBus.connect(testRootDisposable).subscribe(AiMemoStore.TOPIC, AiMemoListener { changes++ })
  }

  private val text = (0..9).joinToString("\n") { "line $it" }

  fun testAddAndList() {
    val doc = myFixture.configureByText("A.java", text).virtualFile.let { myFixture.editor.document }
    store.add(doc, 2, 4, "fix")
    val memo = store.all().single()
    assertEquals(2, memo.startLine)
    assertEquals(4, memo.endLine)
    assertEquals("fix", memo.text)
    val item = store.toItem(memo)!!
    assertEquals("A.java", item.path)
    assertEquals(3, item.startLine)
    assertEquals(5, item.endLine)
    assertEquals("line 2\nline 3\nline 4", item.code)
    assertEquals("java", item.language)
  }

  fun testRangeFollowsEdits() {
    val doc = myFixture.configureByText("A.java", text).virtualFile.let { myFixture.editor.document }
    store.add(doc, 2, 4, "fix")
    WriteCommandAction.runWriteCommandAction(project) { doc.insertString(0, "x\ny\n") }
    val memo = store.all().single()
    assertEquals(4, memo.startLine)
    assertEquals(6, memo.endLine)
  }

  fun testMemoRemovedWhenItsCodeIsDeleted() {
    val doc = myFixture.configureByText("A.java", text).virtualFile.let { myFixture.editor.document }
    store.add(doc, 2, 4, "fix")
    changes = 0
    WriteCommandAction.runWriteCommandAction(project) { doc.deleteString(doc.getLineStartOffset(2), doc.getLineEndOffset(4)) }
    assertTrue(store.all().isEmpty())
    assertTrue(changes >= 1)
  }

  fun testOrderAcrossFiles() {
    val b = myFixture.configureByText("B.kt", text).let { myFixture.editor.document }
    store.add(b, 1, 1, "b1")
    val a = myFixture.configureByText("A.java", text).let { myFixture.editor.document }
    store.add(a, 5, 5, "a5")
    store.add(a, 1, 1, "a1")
    assertEquals(listOf("a1", "a5", "b1"), store.all().map { it.text })
  }

  fun testCodeIsCurrentAtToItemTime() {
    val doc = myFixture.configureByText("A.java", text).let { myFixture.editor.document }
    val memo = store.add(doc, 2, 4, "fix")
    WriteCommandAction.runWriteCommandAction(project) { doc.replaceString(doc.getLineStartOffset(3), doc.getLineEndOffset(3), "changed") }
    assertTrue(store.toItem(memo)!!.code.contains("changed"))
  }
}

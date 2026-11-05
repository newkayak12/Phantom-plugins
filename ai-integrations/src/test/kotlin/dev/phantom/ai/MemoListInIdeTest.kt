// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.memo.AiMemoStore
import dev.phantom.ai.memo.MemoListPanel
import dev.phantom.ai.memo.MemoSender
import dev.phantom.ai.memo.MemoTarget
import dev.phantom.ai.memo.SendOutcome
import dev.phantom.ai.terminal.AgentTab
import dev.phantom.ai.terminal.AgentTabsHost
import dev.phantom.ai.terminal.ScreenSnapshot
import dev.phantom.ai.terminal.TerminalIo
import javax.swing.JPanel

class MemoListInIdeTest : BasePlatformTestCase() {
  private class FakeIo : TerminalIo {
    override fun snapshot() = ScreenSnapshot(emptyList(), 80, 24, 0, 0)
    override fun send(text: String) {}
    override fun onChange(parent: Disposable, listener: () -> Unit) {}
  }

  private lateinit var store: AiMemoStore
  private lateinit var sender: MemoSender
  private lateinit var panel: MemoListPanel
  private lateinit var doc: com.intellij.openapi.editor.Document

  override fun setUp() {
    super.setUp()
    store = AiMemoStore(project) { "/src" }
    Disposer.register(testRootDisposable, store)
    sender = MemoSender(project, store) { path -> VirtualFileManager.getInstance().findFileByUrl("temp://$path") }
    Disposer.register(testRootDisposable, sender)
    myFixture.configureByText("A.java", (0..9).joinToString("\n") { "line $it" })
    doc = myFixture.editor.document
    panel = MemoListPanel(project, store, sender)
    Disposer.register(testRootDisposable, panel)
  }

  override fun tearDown() {
    try {
      AgentTabsHost.getInstance(project).let { h -> h.tabs().forEach { h.close(it.id) } }
      SessionStore.getInstance().all().forEach { SessionStore.getInstance().remove(it.tabId) }
    } finally {
      super.tearDown()
    }
  }

  private fun flush() = PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

  private fun workingSession() {
    SessionStore.getInstance().put(AgentSession("t1", "/src", AgentKind.CLAUDE, status = AgentStatus.WORKING))
    val disposable = Disposer.newDisposable("tab")
    Disposer.register(disposable) { SessionStore.getInstance().remove("t1") }
    AgentTabsHost.getInstance(project).add(AgentTab("t1", "t1", JPanel(), io = FakeIo(), disposable = disposable))
  }

  fun testListShowsMemosInOrder() {
    store.add(doc, 8, 8, "m2")
    store.add(doc, 2, 4, "m1")
    flush()
    assertEquals(listOf("A.java 3–5 · m1", "A.java 9 · m2"), panel.rowsForTest())
  }

  fun testPanelHiddenWithoutMemos() {
    assertFalse(panel.isVisible)
    store.add(doc, 0, 0, "m")
    flush()
    assertTrue(panel.isVisible)
  }

  fun testSendDisabledWithNothingChecked() {
    val memo = store.add(doc, 0, 0, "m")
    flush()
    assertTrue(panel.sendButtonForTest().isEnabled)
    store.setChecked(memo.id, false)
    flush()
    assertFalse(panel.sendButtonForTest().isEnabled)
  }

  fun testQueuedBatchShowsAndCancelRestores() {
    workingSession()
    val memo = store.add(doc, 1, 1, "m1")
    assertEquals(SendOutcome.QUEUED, sender.send(MemoTarget.Session("t1", "t1", AgentStatus.WORKING), listOf(memo.id)))
    flush()
    assertEquals(1, panel.rowsForTest().size)
    assertTrue(panel.rowsForTest().single().startsWith("대기 중 →"))
    assertTrue(panel.isVisible)
    panel.cancelButtonsForTest().single().doClick()
    flush()
    assertEquals(listOf("A.java 2 · m1"), panel.rowsForTest())
  }
}

// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.memo.AiMemoStore
import dev.phantom.ai.memo.MemoSender
import dev.phantom.ai.memo.MemoTarget
import dev.phantom.ai.memo.SendOutcome
import dev.phantom.ai.terminal.AgentTab
import dev.phantom.ai.terminal.AgentTabsHost
import dev.phantom.ai.terminal.AgentTerminalService
import dev.phantom.ai.terminal.ScreenSnapshot
import dev.phantom.ai.terminal.StartedTerminal
import dev.phantom.ai.terminal.TerminalIo
import dev.phantom.ai.terminal.TerminalStarter
import javax.swing.JPanel

class MemoSenderInIdeTest : BasePlatformTestCase() {
  private class FakeIo : TerminalIo {
    val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
    override fun snapshot() = ScreenSnapshot(emptyList(), 80, 24, 0, 0)
    override fun send(text: String) {
      sent += text
    }
    override fun onChange(parent: Disposable, listener: () -> Unit) {}
  }

  private val commands = mutableListOf<String>()
  private val io = FakeIo()
  private lateinit var store: AiMemoStore
  private lateinit var sender: MemoSender
  private lateinit var doc: com.intellij.openapi.editor.Document

  override fun setUp() {
    super.setUp()
    AgentTerminalService.getInstance(project).replaceStarter(TerminalStarter { _: Disposable, _, _, command ->
      commands.add(command)
      StartedTerminal(JPanel())
    })
    store = AiMemoStore(project) { "/src" }
    Disposer.register(testRootDisposable, store)
    sender = MemoSender(project, store) { path -> VirtualFileManager.getInstance().findFileByUrl("temp://$path") }
    Disposer.register(testRootDisposable, sender)
    myFixture.configureByText("A.java", (0..9).joinToString("\n") { "line $it" })
    doc = myFixture.editor.document
  }

  override fun tearDown() {
    try {
      AgentTabsHost.getInstance(project).let { h -> h.tabs().forEach { h.close(it.id) } }
      SessionStore.getInstance().all().forEach { SessionStore.getInstance().remove(it.tabId) }
      flush()
    } finally {
      super.tearDown()
    }
  }

  private fun session(status: AgentStatus?): String {
    SessionStore.getInstance().put(AgentSession("t1", "/src", AgentKind.CLAUDE, status = status))
    AgentTabsHost.getInstance(project).add(AgentTab("t1", "t1", JPanel(), io = io, disposable = Disposer.newDisposable("tab")))
    return "t1"
  }

  private fun flush() = PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

  fun testSendToIdleSessionSubmitsAndRemovesMemos() {
    val tab = session(AgentStatus.DONE)
    val memo = store.add(doc, 1, 2, "fix")
    assertEquals(SendOutcome.SENT, sender.send(MemoTarget.Session(tab, "t", AgentStatus.DONE), listOf(memo.id)))
    assertTrue(io.sent.first(), io.sent.first().startsWith("\u001b[200~아래 코드 위치마다"))
    assertTrue(store.all().isEmpty())
    assertEquals(tab, sender.lastUsed())
  }

  fun testBusySessionQueuesAndRemovesMemos() {
    val tab = session(AgentStatus.WORKING)
    val memo = store.add(doc, 1, 2, "fix")
    assertEquals(SendOutcome.QUEUED, sender.send(MemoTarget.Session(tab, "t", AgentStatus.WORKING), listOf(memo.id)))
    assertTrue(io.sent.isEmpty())
    assertTrue(store.all().isEmpty())
    assertEquals(1, sender.queue().pending().size)
  }

  fun testNewSessionGetsPromptAtLaunch() {
    val memo = store.add(doc, 1, 2, "fix")
    assertEquals(SendOutcome.STARTED, sender.send(MemoTarget.New(AgentKind.CLAUDE), listOf(memo.id)))
    assertTrue(commands.last(), commands.last().contains("아래 코드 위치마다"))
    assertTrue(store.all().isEmpty())
  }

  fun testUncheckedMemosStay() {
    val tab = session(AgentStatus.DONE)
    val a = store.add(doc, 1, 1, "a")
    store.add(doc, 3, 3, "b")
    sender.send(MemoTarget.Session(tab, "t", AgentStatus.DONE), listOf(a.id))
    assertEquals(listOf("b"), store.all().map { it.text })
  }

  fun testDocumentsAreSavedBeforeSend() {
    val tab = session(AgentStatus.DONE)
    val memo = store.add(doc, 1, 1, "a")
    WriteCommandAction.runWriteCommandAction(project) { doc.insertString(0, "x") }
    assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(doc))
    sender.send(MemoTarget.Session(tab, "t", AgentStatus.DONE), listOf(memo.id))
    assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(doc))
  }

  fun testReturnedBatchRestoresMemos() {
    val tab = session(AgentStatus.WORKING)
    val memo = store.add(doc, 1, 2, "fix")
    sender.send(MemoTarget.Session(tab, "t", AgentStatus.WORKING), listOf(memo.id))
    AgentTabsHost.getInstance(project).close(tab)
    flush()
    val back = store.all().single()
    assertEquals(1, back.startLine)
    assertEquals(2, back.endLine)
    assertEquals("fix", back.text)
    assertTrue(sender.queue().pending().isEmpty())
  }

  fun testCancelRestoresMemos() {
    val tab = session(AgentStatus.WORKING)
    val memo = store.add(doc, 1, 2, "fix")
    sender.send(MemoTarget.Session(tab, "t", AgentStatus.WORKING), listOf(memo.id))
    val batch = sender.queue().pending().single()
    assertTrue(sender.cancel(batch.id))
    assertFalse(sender.cancel(batch.id))
    assertEquals("fix", store.all().single().text)
    assertTrue(sender.queue().pending().isEmpty())
  }
}

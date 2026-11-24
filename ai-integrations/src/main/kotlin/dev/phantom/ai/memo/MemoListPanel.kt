// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.memo

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.SessionListener
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.core.WhenShown
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The memos of the project (and the batches waiting for a busy session) under the worktree tree; hidden while there are none.
 * Its send button lives in the tool window's button row (with Board and Dashboard), so it is there with the tree folded too.
 */
class MemoListPanel(
  private val project: Project,
  private val store: AiMemoStore = AiMemoStore.getInstance(project),
  private val sender: MemoSender = MemoSender.getInstance(project),
) : JPanel(BorderLayout()), Disposable {
  private val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
  /** "N개 한 번에 보내기": placed by the tool window; shown while there are memos. */
  val sendButton = JButton().apply {
    name = "btn.memoSend"
    isFocusable = false
    isVisible = false
    addActionListener { MemoSendPopup.show(project, this) }
  }
  private val rowTexts = mutableListOf<String>()
  // session events come several a second while agents work and only rename queued batches: not while hidden. Memo and
  // queue events still refresh at once, as they are what makes this panel visible
  private val whenShown = WhenShown(this, ::refreshLater)
  private val cancelButtons = mutableListOf<JButton>()

  init {
    name = "toolwindow.memoList"
    val scroll = JBScrollPane(rows).apply { border = JBUI.Borders.empty(); preferredSize = Dimension(0, JBUI.scale(140)) }
    add(scroll, BorderLayout.CENTER)
    val connection = project.messageBus.connect(this)
    connection.subscribe(AiMemoStore.TOPIC, AiMemoListener { refreshLater() })
    connection.subscribe(SessionStore.TOPIC, SessionListener { _, _ -> whenShown.request() })
    connection.subscribe(MemoSender.TOPIC, MemoQueueListener { refreshLater() })
    refresh()
  }

  private fun refreshLater() {
    // always deferred: a document change may be what triggered this
    ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed)
  }

  private fun refresh() {
    rows.removeAll()
    rowTexts.clear()
    cancelButtons.clear()
    val memos = store.all()
    val batches = sender.queue().pending()
    for (batch in batches) {
      val label = sender.choices().first.filterIsInstance<MemoTarget.Session>().firstOrNull { it.tabId == batch.tabId }?.label ?: batch.tabId
      val text = PhantomAiBundle.message("memo.list.queued", label, batch.items.size)
      val cancel = JButton(PhantomAiBundle.message("memo.list.cancel")).apply {
        name = "btn.memoCancel"
        isFocusable = false
        addActionListener { sender.cancel(batch.id); refresh() }
      }
      cancelButtons += cancel
      rowTexts += text
      rows.add(JPanel(BorderLayout()).apply {
        add(JLabel(text), BorderLayout.CENTER)
        add(cancel, BorderLayout.EAST)
      })
    }
    for (memo in memos) {
      val start = memo.startLine + 1
      val end = memo.endLine + 1
      val range = if (end > start) "$start–$end" else "$start"
      val text = "${memo.file.name} $range · ${memo.text}"
      rowTexts += text
      val check = JCheckBox().apply {
        isSelected = memo.checked
        isFocusable = false
        addActionListener { store.setChecked(memo.id, isSelected) }
      }
      val label = JLabel(text).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
          override fun mouseClicked(e: MouseEvent) {
            OpenFileDescriptor(project, memo.file, memo.startLine, 0).navigate(true)
          }
        })
      }
      rows.add(JPanel(BorderLayout()).apply {
        add(check, BorderLayout.WEST)
        add(label, BorderLayout.CENTER)
      })
    }
    val checked = memos.count { it.checked }
    sendButton.text = PhantomAiBundle.message("memo.send", checked)
    sendButton.isEnabled = checked > 0
    sendButton.isVisible = memos.isNotEmpty()
    sendButton.parent?.revalidate()
    isVisible = memos.isNotEmpty() || batches.isNotEmpty()
    rows.revalidate()
    rows.repaint()
    parent?.revalidate()
  }

  override fun dispose() {}

  @TestOnly fun rowsForTest(): List<String> = rowTexts.toList()

  @TestOnly fun sendButtonForTest(): JButton = sendButton

  @TestOnly fun cancelButtonsForTest(): List<JButton> = cancelButtons.toList()
}
